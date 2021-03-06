package ru.job4j.grabber;

import org.quartz.*;
import org.quartz.impl.StdSchedulerFactory;

import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.List;
import java.util.Properties;

import static org.quartz.JobBuilder.newJob;
import static org.quartz.SimpleScheduleBuilder.simpleSchedule;
import static org.quartz.TriggerBuilder.newTrigger;

public class Grabber implements Grab {
    private final Properties cfg = new Properties();

    /**
     * Экземпляр хранилища (БД)
     * @return хранилище (БД)
     */
    public Store store() {
        return new PsqlStore(this.cfg);
    }

    public Scheduler scheduler() throws SchedulerException {
        Scheduler scheduler = StdSchedulerFactory.getDefaultScheduler();
        scheduler.start();
        return scheduler;
    }

    /**
     * Получаем необходимые свойства(адрес БД, порт, интервал, учетные данные и тд) для выполнения
     * @throws IOException исключения ввода-вывода
     */
    public void cfg() throws IOException {
        try (InputStream in = PsqlStore.class.getClassLoader().getResourceAsStream("rabbit.properties")) {
            cfg.load(in);
        }
    }

    /**
     * Инициализация(настройка) Планировщика, добавление в Карту парсера, хранилища,
     * учтановка интервала и триггера
     * @param parse Объект выполняющий парсинг цели
     * @param store Хранилище записей (результатов парсинга)
     * @param scheduler Планировщик выполняющий периодический запуск выполнения Работ
     * @throws SchedulerException исключения планировщика
     */
    @Override
    public void init(Parse parse, Store store, Scheduler scheduler) throws SchedulerException {
        JobDataMap data = new JobDataMap();
        data.put("store", store);
        data.put("parse", parse);
        JobDetail job = newJob(GrabJob.class)
                .usingJobData(data)
                .build();
        SimpleScheduleBuilder times = simpleSchedule()
                .withIntervalInHours(Integer.parseInt(cfg.getProperty("rabbit.interval")))
                .repeatForever();
        Trigger trigger = newTrigger()
                .startNow()
                .withSchedule(times)
                .build();
        scheduler.scheduleJob(job, trigger);
    }

    public static class GrabJob implements Job {

        /**
         * Метод выполняет Работу по получение всех записей по ссылке (метод List)
         * и сохраняет в хранилище (БД) (метод save)
         * @param context контекст в котором выполняется Работа
         * @throws JobExecutionException исключение при выполнении Работы
         */
        @Override
        public void execute(JobExecutionContext context) throws JobExecutionException {
            JobDataMap map = context.getJobDetail().getJobDataMap();
            Store store = (Store) map.get("store");
            Parse parse = (Parse) map.get("parse");
            String link = (String) map.get("source.link");
            List<Post> list = parse.list(link);
            for (Post p : list) {
                store.save(p);
            }
        }
    }

    /**
     * Метод получает все записи из хранилища store
     * и отображает на Веб форме браузера по указаннмоу порту в настройках
     * @param store Хранилище (БД) с записями
     */
    public void web(Store store) {
        new Thread(() -> {
            try (ServerSocket server = new ServerSocket(Integer.parseInt(cfg.getProperty("web.interface.port")))) {
                while (!server.isClosed()) {
                    Socket socket = server.accept();
                    try (OutputStreamWriter out = new OutputStreamWriter(socket.getOutputStream(), "windows-1251")) {
                        out.write("HTTP/1.1 200 OK\r\n\r\n");
                        for (Post post : store.getAll()) {
                            out.write(post.toString());
                            out.write(System.lineSeparator());
                        }
                    } catch (IOException io) {
                        io.printStackTrace();
                    }
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }).start();
    }


    public static void main(String[] args) throws Exception {
        Grabber grab = new Grabber();
        grab.cfg();
        Scheduler scheduler = grab.scheduler();
        Store store = grab.store();
        grab.init(new SqlRuParse(), store, scheduler);
        grab.web(store);
    }
}
package example;

import io.github.simpledi.BeanContext;
import io.github.simpledi.XmlBeans;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;

public final class Demo {
    private Demo() {}

    public record Repository(String url) {}

    public static final class Service {
        private final Repository repository;
        private final Clock clock;
        private Duration timeout;

        public Service(Repository repository, Clock clock) {
            this.repository = repository;
            this.clock = clock;
        }

        public void setTimeout(Duration timeout) {
            this.timeout = timeout;
        }

        public void start() {
            System.out.println("started " + repository.url() + " at " + clock.instant()
                    + " timeout=" + timeout);
        }

        public void stop() {
            System.out.println("stopped");
        }
    }

    public static void main(String[] args) {
        try (BeanContext context = XmlBeans.builder()
                .propertiesFile(Path.of("examples/application.properties"))
                .property("db.url", "jdbc:development")
                .overlay(Path.of("examples/development.xml"))
                .load(Path.of("examples/beans.xml"))) {
            context.require("service", Service.class);
        }
    }
}

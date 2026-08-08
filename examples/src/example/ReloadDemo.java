package example;

import io.github.simpledi.ContextLease;
import io.github.simpledi.ReloadResult;
import io.github.simpledi.ReloadableBeanContext;
import io.github.simpledi.XmlBeans;

import java.nio.file.Path;

public final class ReloadDemo {
    private ReloadDemo() {}

    public record Service(String version) {}

    public static void main(String[] args) {
        try (ReloadableBeanContext manager = XmlBeans.reloadable()
                .load(Path.of("examples/reload-v1.xml"))) {
            try (ContextLease lease = manager.acquire()) {
                System.out.println("generation " + lease.generation() + ": "
                        + lease.require("service", Service.class).version());
            }

            ReloadResult result = manager.reload(Path.of("examples/reload-v2.xml"));
            if (!result.activated()) throw new IllegalStateException("reload failed: " + result);

            try (ContextLease lease = manager.acquire()) {
                System.out.println("generation " + lease.generation() + ": "
                        + lease.require("service", Service.class).version());
            }
        }
    }
}

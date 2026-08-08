/** Explicit, JDK-only XML dependency injection and object graph assembly. */
module io.github.simpledi {
    requires java.xml;
    requires static java.compiler;
    exports io.github.simpledi;
}

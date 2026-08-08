package io.github.simpledi;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/** Manager-local weak identity numbering used only while producing opaque revisions. */
final class RevisionIdentityRegistry {
    private record Entry(WeakReference<Object> reference, long id) {}
    private final List<Entry> entries = new ArrayList<>();
    private long nextId;

    synchronized long id(Object value) {
        Iterator<Entry> iterator = entries.iterator();
        while (iterator.hasNext()) {
            Entry entry = iterator.next();
            Object candidate = entry.reference().get();
            if (candidate == null) iterator.remove();
            else if (candidate == value) return entry.id();
        }
        long id = ++nextId;
        entries.add(new Entry(new WeakReference<>(value), id));
        return id;
    }
}

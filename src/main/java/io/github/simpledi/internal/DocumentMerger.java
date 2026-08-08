package io.github.simpledi.internal;

import io.github.simpledi.BeanException;
import io.github.simpledi.internal.Definitions.BeanDef;
import io.github.simpledi.internal.Definitions.Document;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

/** Deterministically merges a base document with explicit overlay documents. */
public final class DocumentMerger {
    private DocumentMerger() {}

    public static Document merge(Document base, List<Document> overlays) {
        LinkedHashMap<String, BeanDef> beans = new LinkedHashMap<>();
        for (BeanDef bean : base.beans().values()) {
            if (bean.replaces() != null) {
                throw new BeanException(bean.location(), "replaces is valid only in an overlay document");
            }
            beans.put(bean.id(), bean);
        }
        LinkedHashMap<String, String> aliases = new LinkedHashMap<>(base.aliases());
        var conditions = new ArrayList<>(base.conditions());

        for (Document overlay : overlays) {
            conditions.addAll(overlay.conditions());
            for (BeanDef bean : overlay.beans().values()) {
                String replaces = bean.replaces();
                if (replaces == null) {
                    if (beans.containsKey(bean.id()) || aliases.containsKey(bean.id())) {
                        throw new BeanException(bean.location(), "Overlay bean '" + bean.id()
                                + "' collides with an existing name; declare replaces=\"" + bean.id() + "\"");
                    }
                    beans.put(bean.id(), bean);
                    continue;
                }
                if (!replaces.equals(bean.id())) {
                    throw new BeanException(bean.location(), "Overlay replacement must keep the same id: bean id '"
                            + bean.id() + "' cannot replace '" + replaces + "'");
                }
                if (!beans.containsKey(replaces)) {
                    throw new BeanException(bean.location(), "Overlay bean '" + bean.id()
                            + "' replaces unknown bean '" + replaces + "'");
                }
                beans.put(bean.id(), bean);
            }
            for (var alias : overlay.aliases().entrySet()) {
                if (beans.containsKey(alias.getKey()) || aliases.containsKey(alias.getKey())) {
                    throw new BeanException("Overlay alias '" + alias.getKey() + "' collides with an existing name");
                }
                aliases.put(alias.getKey(), alias.getValue());
            }
        }
        return new Document(beans, aliases, conditions);
    }
}

package io.github.simpledi;

/** Defensive XML/configuration limits. Byte limits also apply as character limits to Reader/string inputs. */
public record XmlLimits(
        int maxDocuments,
        int maxElements,
        int maxDepth,
        int maxAttributesPerElement,
        int maxTextLength,
        int maxBeans,
        long maxDocumentBytes,
        long maxTotalBytes,
        long maxMiscTextLength) {

    public static final XmlLimits DEFAULT = new XmlLimits(
            128, 100_000, 128, 64, 1_000_000, 20_000,
            8L * 1024 * 1024, 32L * 1024 * 1024, 1_000_000);

    /** Source-compatible constructor for the 2.0-2.2 limits. */
    public XmlLimits(int maxDocuments, int maxElements, int maxDepth, int maxAttributesPerElement,
                     int maxTextLength, int maxBeans) {
        this(maxDocuments, maxElements, maxDepth, maxAttributesPerElement, maxTextLength, maxBeans,
                DEFAULT.maxDocumentBytes, DEFAULT.maxTotalBytes, DEFAULT.maxMiscTextLength);
    }

    public XmlLimits {
        if (maxDocuments < 1 || maxElements < 1 || maxDepth < 1 || maxAttributesPerElement < 1
                || maxTextLength < 1 || maxBeans < 1 || maxDocumentBytes < 1 || maxTotalBytes < 1
                || maxMiscTextLength < 1) {
            throw new IllegalArgumentException("All XML limits must be positive");
        }
        if (maxDocumentBytes > maxTotalBytes) {
            throw new IllegalArgumentException("maxDocumentBytes must not exceed maxTotalBytes");
        }
    }
}

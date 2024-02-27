Contains converter-*like* extraction jobs that operate on crawled data to produce export files.

## Important classes

* [AtagExporter](java/nu/marginalia/extractor/AtagExporter.java) - extracts anchor texts from the crawled data.
* [FeedExporter](java/nu/marginalia/extractor/FeedExporter.java) - tries to find RSS/Atom feeds within the crawled data.
* [TermFrequencyExporter](java/nu/marginalia/extractor/TermFrequencyExporter.java) - exports the 'TF' part of TF-IDF.
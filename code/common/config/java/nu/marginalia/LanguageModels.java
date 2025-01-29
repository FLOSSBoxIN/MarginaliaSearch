package nu.marginalia;

import java.nio.file.Path;

public class LanguageModels {
    public final Path termFrequencies;

    public final Path openNLPSentenceDetectionData;
    public final Path posRules;
    public final Path posDict;
    public final Path fasttextLanguageModel;
    public final Path segments;

    public LanguageModels(Path termFrequencies,
                          Path openNLPSentenceDetectionData,
                          Path posRules,
                          Path posDict,
                          Path fasttextLanguageModel,
                          Path segments) {
        this.termFrequencies = termFrequencies;
        this.openNLPSentenceDetectionData = openNLPSentenceDetectionData;
        this.posRules = posRules;
        this.posDict = posDict;
        this.fasttextLanguageModel = fasttextLanguageModel;
        this.segments = segments;
    }
}

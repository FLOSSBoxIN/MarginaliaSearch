package nu.marginalia;

import lombok.Builder;

import java.nio.file.Path;

@Builder
public class LanguageModels {
    public final Path termFrequencies;

    public final Path openNLPSentenceDetectionData;
    public final Path posRules;
    public final Path posDict;
    public final Path openNLPTokenData;
    public final Path fasttextLanguageModel;
    public final Path segments;

    public LanguageModels(Path termFrequencies,
                          Path openNLPSentenceDetectionData,
                          Path posRules,
                          Path posDict,
                          Path openNLPTokenData,
                          Path fasttextLanguageModel,
                          Path segments) {
        this.termFrequencies = termFrequencies;
        this.openNLPSentenceDetectionData = openNLPSentenceDetectionData;
        this.posRules = posRules;
        this.posDict = posDict;
        this.openNLPTokenData = openNLPTokenData;
        this.fasttextLanguageModel = fasttextLanguageModel;
        this.segments = segments;
    }
}

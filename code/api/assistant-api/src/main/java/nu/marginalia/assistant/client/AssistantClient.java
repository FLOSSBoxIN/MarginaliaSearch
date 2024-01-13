package nu.marginalia.assistant.client;

import com.google.gson.reflect.TypeToken;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import io.reactivex.rxjava3.core.Observable;
import nu.marginalia.assistant.client.model.DictionaryResponse;
import nu.marginalia.assistant.client.model.DomainInformation;
import nu.marginalia.assistant.client.model.SimilarDomain;
import nu.marginalia.client.AbstractDynamicClient;
import nu.marginalia.client.exception.RouteNotConfiguredException;
import nu.marginalia.model.gson.GsonFactory;
import nu.marginalia.service.descriptor.ServiceDescriptors;
import nu.marginalia.service.id.ServiceId;
import nu.marginalia.client.Context;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

@Singleton
public class AssistantClient extends AbstractDynamicClient {

    @Inject
    public AssistantClient(ServiceDescriptors descriptors) {
        super(descriptors.forId(ServiceId.Assistant), GsonFactory::get);
    }

    public Observable<DictionaryResponse> dictionaryLookup(Context ctx, String word) {
        try {
            return super.get(ctx, 0, "/dictionary/" + URLEncoder.encode(word, StandardCharsets.UTF_8), DictionaryResponse.class);
        }
        catch (RouteNotConfiguredException ex) {
            return Observable.empty();
        }
    }

    @SuppressWarnings("unchecked")
    public Observable<List<String>> spellCheck(Context ctx, String word) {
        try {
            return (Observable<List<String>>) (Object) super.get(ctx, 0, "/spell-check/" +  URLEncoder.encode(word, StandardCharsets.UTF_8), List.class);
        }
        catch (RouteNotConfiguredException ex) {
            return Observable.empty();
        }
    }
    public Observable<String> unitConversion(Context ctx, String value, String from, String to) {
        try {
            return super.get(ctx, 0, "/unit-conversion?value=" + value + "&from=" + from + "&to=" + to);
        }
        catch (RouteNotConfiguredException ex) {
            return Observable.empty();
        }
    }

    public Observable<String> evalMath(Context ctx, String expression) {
        try {
            return super.get(ctx, 0, "/eval-expression?value=" +  URLEncoder.encode(expression, StandardCharsets.UTF_8));
        }
        catch (RouteNotConfiguredException ex) {
            return Observable.empty();
        }
    }

    public Observable<ArrayList<SimilarDomain>> similarDomains(Context ctx, int domainId, int count) {
        try {
            return super.get(ctx, 0, STR."/domain/\{domainId}/similar?count=\{count}", new TypeToken<ArrayList<SimilarDomain>>() {})
                    .onErrorResumeWith(Observable.just(new ArrayList<>()));
        }
        catch (RouteNotConfiguredException ex) {
            return Observable.empty();
        }
    }

    public Observable<ArrayList<SimilarDomain>> linkedDomains(Context ctx, int domainId, int count) {
        try {
            return super.get(ctx, 0, STR."/domain/\{domainId}/linking?count=\{count}", new TypeToken<ArrayList<SimilarDomain>>() {})
                    .onErrorResumeWith(Observable.just(new ArrayList<>()));
        }
        catch (RouteNotConfiguredException ex) {
            return Observable.empty();
        }
    }

    public Observable<DomainInformation> domainInformation(Context ctx, int domainId) {
        try {
            return super.get(ctx, 0, STR."/domain/\{domainId}/info", DomainInformation.class)
                    .onErrorResumeWith(Observable.just(new DomainInformation()));
        }
        catch (RouteNotConfiguredException ex) {
            return Observable.empty();
        }
    }
}

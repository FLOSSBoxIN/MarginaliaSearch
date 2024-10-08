package nu.marginalia.parquet.crawldata;

import blue.strategic.parquet.Dehydrator;
import blue.strategic.parquet.Hydrator;
import blue.strategic.parquet.ValueWriter;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.ToString;
import org.apache.parquet.schema.MessageType;
import org.apache.parquet.schema.Types;

import java.time.Instant;

import static org.apache.parquet.schema.LogicalTypeAnnotation.stringType;
import static org.apache.parquet.schema.PrimitiveType.PrimitiveTypeName.*;

@AllArgsConstructor
@NoArgsConstructor
@EqualsAndHashCode
@ToString
public class CrawledDocumentParquetRecord {
    public String domain;
    public String url;
    public String ip;
    public boolean cookies;
    public int httpStatus;
    public Instant timestamp;
    public String contentType;
    public byte[] body;

    public String headers;

    @Deprecated // will be replaced with the full headers field in the future
    public String etagHeader;
    @Deprecated // will be replaced with the full headers field in the future
    public String lastModifiedHeader;

    public static Hydrator<CrawledDocumentParquetRecord, CrawledDocumentParquetRecord> newHydrator() {
        return new CrawledDocumentParquetRecordHydrator();
    }

    public static Dehydrator<CrawledDocumentParquetRecord> newDehydrator() {
        return CrawledDocumentParquetRecord::dehydrate;
    }

    public static MessageType schema = new MessageType(
            CrawledDocumentParquetRecord.class.getSimpleName(),
            Types.required(BINARY).as(stringType()).named("domain"),
            Types.required(BINARY).as(stringType()).named("url"),
            Types.required(BINARY).as(stringType()).named("ip"),
            Types.required(BOOLEAN).named("cookies"),
            Types.required(INT32).named("httpStatus"),
            Types.required(INT64).named("epochSeconds"),
            Types.required(BINARY).as(stringType()).named("contentType"),
            Types.required(BINARY).named("body"),
            Types.optional(BINARY).as(stringType()).named("etagHeader"),
            Types.optional(BINARY).as(stringType()).named("lastModifiedHeader"),
            Types.optional(BINARY).as(stringType()).named("headers")
    );


    public CrawledDocumentParquetRecord add(String heading, Object value) {
        switch (heading) {
            case "domain" -> domain = (String) value;
            case "url" -> url = (String) value;
            case "ip" -> ip = (String) value;
            case "httpStatus" -> httpStatus = (Integer) value;
            case "cookies" -> cookies = (Boolean) value;
            case "contentType" -> contentType = (String) value;
            case "body" -> body = (byte[]) value;
            case "epochSeconds" -> timestamp = Instant.ofEpochSecond((Long) value);
            case "etagHeader" -> etagHeader = (String) value;
            case "lastModifiedHeader" -> lastModifiedHeader = (String) value;
            case "headers" -> headers = (String) value;

            default -> throw new UnsupportedOperationException("Unknown heading '" + heading + '"');
        }
        return this;
    }

    public void dehydrate(ValueWriter valueWriter) {
        valueWriter.write("domain", domain);
        valueWriter.write("url", url);
        valueWriter.write("ip", ip);
        valueWriter.write("epochSeconds", timestamp.getEpochSecond());
        valueWriter.write("httpStatus", httpStatus);
        valueWriter.write("cookies", cookies);
        valueWriter.write("contentType", contentType);
        valueWriter.write("body", body);
        if (headers != null) {
            valueWriter.write("headers", headers);
        }
        if (etagHeader != null) {
            valueWriter.write("etagHeader", etagHeader);
        }
        if (lastModifiedHeader != null) {
            valueWriter.write("lastModifiedHeader", lastModifiedHeader);
        }
    }
}

class CrawledDocumentParquetRecordHydrator implements Hydrator<CrawledDocumentParquetRecord, CrawledDocumentParquetRecord> {

    @Override
    public CrawledDocumentParquetRecord start() {
        return new CrawledDocumentParquetRecord();
    }

    @Override
    public CrawledDocumentParquetRecord add(CrawledDocumentParquetRecord target, String heading, Object value) {
        return target.add(heading, value);
    }

    @Override
    public CrawledDocumentParquetRecord finish(CrawledDocumentParquetRecord target) {
        return target;
    }

}

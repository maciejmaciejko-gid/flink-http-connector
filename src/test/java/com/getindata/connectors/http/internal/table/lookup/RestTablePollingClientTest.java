package com.getindata.connectors.http.internal.table.lookup;

import java.net.http.HttpClient;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.matching.RequestPatternBuilder;
import com.github.tomakehurst.wiremock.stubbing.StubMapping;
import org.apache.flink.api.common.serialization.DeserializationSchema;
import org.apache.flink.configuration.Configuration;
import org.apache.flink.formats.json.JsonFormatFactory;
import org.apache.flink.table.api.DataTypes;
import org.apache.flink.table.connector.source.DynamicTableSource;
import org.apache.flink.table.data.RowData;
import org.apache.flink.table.factories.DynamicTableFactory.Context;
import org.apache.flink.table.runtime.connector.source.LookupRuntimeProviderContext;
import org.apache.flink.table.types.DataType;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import static com.getindata.connectors.http.TestHelper.readTestFile;

@ExtendWith(MockitoExtension.class)
class RestTablePollingClientTest {

    private static final String SAMPLES_FOLDER = "/http/";

    private static final List<String> LOOKUP_KEYS = List.of("id", "uuid");

    private static WireMockServer wireMockServer;

    @Mock
    private Context dynamicTableFactoryContext;

    private DynamicTableSource.Context dynamicTableSourceContext;

    private StubMapping stubMapping;

    @BeforeAll
    public static void setUpAll() {
        wireMockServer = new WireMockServer();
        wireMockServer.start();
    }

    @AfterAll
    public static void cleanUpAll() {
        wireMockServer.stop();
    }

    @BeforeEach
    public void setUp() {
        int[][] lookupKey = {{}};
        dynamicTableSourceContext = new LookupRuntimeProviderContext(lookupKey);
    }

    @AfterEach
    public void cleanUp() {
        if (stubMapping != null) {
            wireMockServer.removeStub(stubMapping);
        }
    }

    @Test
    void shouldQuery200WithParams() {
        stubMapping = setupServerStub("/service?id=1&uuid=2", 200);

        RestTablePollingClient pollingClient = setUpPollingClient(getBaseUrl(), LOOKUP_KEYS);

        RowData result = pollingClient.pull(
            List.of(
                new LookupArg("id", "1"),
                new LookupArg("uuid", "2")
            )
        ).orElseThrow();

        wireMockServer.verify(RequestPatternBuilder.forCustomMatcher(stubMapping.getRequest()));

        assertThat(result.getArity()).isEqualTo(4);
        assertThat(result.getString(1)
            .toString()).isEqualTo("Returned HTTP message for parameter PARAM, COUNTER");

        RowData detailsRow = result.getRow(3, 2);
        assertThat(detailsRow.getBoolean(0)).isEqualTo(true);

        RowData nestedDetailsRow = detailsRow.getRow(1, 1);
        assertThat(nestedDetailsRow.getString(0).toString()).isEqualTo("$1,729.34");
    }

    @Test
    void shouldHandleNot200() {
        stubMapping = setupServerStub("/service?id=1&uuid=2", 201);

        RestTablePollingClient pollingClient = setUpPollingClient(getBaseUrl(), LOOKUP_KEYS);

        Optional<RowData> poll = pollingClient.pull(
            List.of(
                new LookupArg("id", "1"),
                new LookupArg("uuid", "2")
            )
        );

        wireMockServer.verify(RequestPatternBuilder.forCustomMatcher(stubMapping.getRequest()));

        assertThat(poll.isEmpty()).isTrue();
    }

    @Test
    void shouldHandleServerError() {
        stubMapping = setupServerStub("/service?id=1&uuid=2", 500);

        RestTablePollingClient pollingClient = setUpPollingClient(getBaseUrl(), LOOKUP_KEYS);

        Optional<RowData> poll = pollingClient.pull(
            List.of(
                new LookupArg("id", "1"),
                new LookupArg("uuid", "2")
            )
        );

        wireMockServer.verify(RequestPatternBuilder.forCustomMatcher(stubMapping.getRequest()));

        assertThat(poll.isEmpty()).isTrue();
    }

    @Test
    void shouldProcessWithMissingArguments() {
        stubMapping = setupServerStub("/service?id=1&uuid=2", 200);

        RestTablePollingClient pollingClient =
            setUpPollingClient(getBaseUrl(), LOOKUP_KEYS);

        Optional<RowData> poll = pollingClient.pull(Collections.emptyList());

        assertThat(poll.isEmpty()).isTrue();
    }

    private String getBaseUrl() {
        return wireMockServer.baseUrl() + "/service";
    }

    public RestTablePollingClient setUpPollingClient(String url, List<String> arguments) {

        HttpLookupConfig expectedLookupConfig = HttpLookupConfig.builder()
            .url(url)
            .arguments(arguments)
            .build();

        DataType physicalDataType = DataTypes.ROW(
            DataTypes.FIELD("id", DataTypes.STRING()),
            DataTypes.FIELD("msg", DataTypes.STRING()),
            DataTypes.FIELD("uuid", DataTypes.STRING()),
            DataTypes.FIELD("details", DataTypes.ROW(
                DataTypes.FIELD("isActive", DataTypes.BOOLEAN()),
                DataTypes.FIELD("nestedDetails", DataTypes.ROW(
                    DataTypes.FIELD("balance", DataTypes.STRING())
                ))
            ))
        );

        DeserializationSchema<RowData> schemaDecoder =
            new JsonFormatFactory()
                .createDecodingFormat(dynamicTableFactoryContext, new Configuration())
                .createRuntimeDecoder(dynamicTableSourceContext, physicalDataType);

        return new RestTablePollingClient(
            HttpClient.newHttpClient(),
            schemaDecoder,
            expectedLookupConfig
        );
    }

    private StubMapping setupServerStub(String paramsPath, int status) {
        return wireMockServer.stubFor(
            get(urlEqualTo(paramsPath))
                .willReturn(
                    aResponse()
                        .withStatus(status)
                        .withBody(readTestFile(SAMPLES_FOLDER + "HttpResult.json"))));
    }
}

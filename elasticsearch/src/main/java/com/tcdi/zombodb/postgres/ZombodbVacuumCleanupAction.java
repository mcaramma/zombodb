package com.tcdi.zombodb.postgres;

import org.elasticsearch.action.ActionRequest;
import org.elasticsearch.action.admin.indices.settings.get.GetSettingsResponse;
import org.elasticsearch.action.bulk.BulkItemResponse;
import org.elasticsearch.action.bulk.BulkRequest;
import org.elasticsearch.action.bulk.BulkResponse;
import org.elasticsearch.action.delete.DeleteRequestBuilder;
import org.elasticsearch.action.search.*;
import org.elasticsearch.client.Client;
import org.elasticsearch.cluster.ClusterService;
import org.elasticsearch.common.inject.Inject;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.unit.TimeValue;
import org.elasticsearch.common.xcontent.json.JsonXContent;
import org.elasticsearch.rest.BaseRestHandler;
import org.elasticsearch.rest.RestChannel;
import org.elasticsearch.rest.RestController;
import org.elasticsearch.rest.RestRequest;
import org.elasticsearch.search.SearchHit;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static com.tcdi.zombodb.postgres.ZombodbBulkAction.buildResponse;
import static org.elasticsearch.index.query.QueryBuilders.*;
import static org.elasticsearch.rest.RestRequest.Method.GET;
import static org.elasticsearch.rest.RestRequest.Method.POST;

public class ZombodbVacuumCleanupAction extends BaseRestHandler {

    private final ClusterService clusterService;

    @Inject
    public ZombodbVacuumCleanupAction(Settings settings, RestController controller, Client client, ClusterService clusterService) {
        super(settings, controller, client);

        this.clusterService = clusterService;

        controller.registerHandler(POST, "/{index}/_zdbvacuum_cleanup", this);
    }

    @Override
    protected void handleRequest(RestRequest request, RestChannel channel, Client client) throws Exception {
        String index = request.param("index");
        Set<Long> xids = new HashSet<>();
        BufferedReader br = new BufferedReader(new InputStreamReader(request.content().streamInput()));
        String line;

        while ( (line = br.readLine()) != null) {
            xids.add(Long.valueOf(line));
        }

        BulkRequest bulkRequest = new BulkRequest();
        BulkResponse response;

        bulkRequest.refresh(request.paramAsBoolean("refresh", false));
        bulkRequest.requests().addAll(cleanupXmax(client, index, xids));

        if (bulkRequest.requests().size() > 0) {
            response = client.bulk(bulkRequest).actionGet();

            channel.sendResponse(buildResponse(response, JsonXContent.contentBuilder()));
        } else {
            channel.sendResponse(buildResponse(new BulkResponse(new BulkItemResponse[0], 0), JsonXContent.contentBuilder()));
        }
    }

    private List<ActionRequest> cleanupXmax(Client client, String index, Set<Long> xids) {
        GetSettingsResponse indexSettings = client.admin().indices().getSettings(client.admin().indices().prepareGetSettings(index).request()).actionGet();
        int shards = Integer.parseInt(indexSettings.getSetting(index, "index.number_of_shards"));
        String[] routingTable = RoutingHelper.getRoutingTable(client, clusterService, index, shards);

        List<ActionRequest> trackingRequests = new ArrayList<>();
        SearchRequestBuilder search = new SearchRequestBuilder(client)
                .setIndices(index)
                .setTypes("xmax")
                .setSearchType(SearchType.SCAN)
                .setScroll(TimeValue.timeValueMinutes(10))
                .setSize(10000)
                .setQuery(termsQuery("_xmax", xids));

        if (!xids.isEmpty()) {
            int total = 0, cnt = 0;
            SearchResponse response = null;
            while (true) {
                if (response == null) {
                    response = client.execute(SearchAction.INSTANCE, search.request()).actionGet();
                    total = (int) response.getHits().getTotalHits();
                } else {
                    response = client.execute(SearchScrollAction.INSTANCE,
                            new SearchScrollRequestBuilder(client)
                                    .setScrollId(response.getScrollId())
                                    .setScroll(TimeValue.timeValueMinutes(10))
                                    .request()).actionGet();
                }

                for (SearchHit hit : response.getHits()) {

                    trackingRequests.add(
                            new DeleteRequestBuilder(client)
                                    .setIndex(index)
                                    .setType("xmax")
                                    .setRouting(hit.id())
                                    .setId(hit.id())
                                    .request()
                    );

                    cnt++;
                }

                if (cnt == total)
                    break;
            }

            for (Long xid : xids) {
                for (String routing : routingTable) {
                    trackingRequests.add(
                            new DeleteRequestBuilder(client)
                                    .setIndex(index)
                                    .setType("aborted")
                                    .setRouting(routing)
                                    .setId(String.valueOf(xid))
                                    .request()
                    );
                }


            }
        }

        return trackingRequests;
    }
}

package com.rbmhtechnology.vind.elasticsearch.backend;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rbmhtechnology.vind.SearchServerException;
import com.rbmhtechnology.vind.annotations.AnnotationUtil;
import com.rbmhtechnology.vind.api.Document;
import com.rbmhtechnology.vind.api.SearchServer;
import com.rbmhtechnology.vind.api.ServiceProvider;
import com.rbmhtechnology.vind.api.query.FulltextSearch;
import com.rbmhtechnology.vind.api.query.delete.Delete;
import com.rbmhtechnology.vind.api.query.get.RealTimeGet;
import com.rbmhtechnology.vind.api.query.suggestion.ExecutableSuggestionSearch;
import com.rbmhtechnology.vind.api.query.update.Update;
import com.rbmhtechnology.vind.api.result.BeanGetResult;
import com.rbmhtechnology.vind.api.result.BeanSearchResult;
import com.rbmhtechnology.vind.api.result.DeleteResult;
import com.rbmhtechnology.vind.api.result.FacetResults;
import com.rbmhtechnology.vind.api.result.GetResult;
import com.rbmhtechnology.vind.api.result.IndexResult;
import com.rbmhtechnology.vind.api.result.PageResult;
import com.rbmhtechnology.vind.api.result.SearchResult;
import com.rbmhtechnology.vind.api.result.SliceResult;
import com.rbmhtechnology.vind.api.result.StatusResult;
import com.rbmhtechnology.vind.api.result.SuggestionResult;
import com.rbmhtechnology.vind.api.result.facet.TermFacetResult;
import com.rbmhtechnology.vind.configure.SearchConfiguration;
import com.rbmhtechnology.vind.elasticsearch.backend.util.DocumentUtil;
import com.rbmhtechnology.vind.elasticsearch.backend.util.ElasticMappingUtils;
import com.rbmhtechnology.vind.elasticsearch.backend.util.ElasticQueryBuilder;
import com.rbmhtechnology.vind.elasticsearch.backend.util.FieldUtil;
import com.rbmhtechnology.vind.elasticsearch.backend.util.PainlessScript;
import com.rbmhtechnology.vind.elasticsearch.backend.util.ResultUtils;
import com.rbmhtechnology.vind.model.DocumentFactory;
import com.rbmhtechnology.vind.model.FieldDescriptor;
import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.lang3.time.StopWatch;
import org.apache.http.util.Asserts;
import org.elasticsearch.ElasticsearchException;
import org.elasticsearch.action.bulk.BulkResponse;
import org.elasticsearch.action.delete.DeleteResponse;
import org.elasticsearch.action.get.MultiGetResponse;
import org.elasticsearch.action.search.SearchResponse;
import org.elasticsearch.action.update.UpdateResponse;
import org.elasticsearch.index.query.QueryBuilder;
import org.elasticsearch.index.query.QueryBuilders;
import org.elasticsearch.index.reindex.BulkByScrollResponse;
import org.elasticsearch.search.builder.SearchSourceBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import sun.reflect.generics.reflectiveObjects.NotImplementedException;

import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.ServiceLoader;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

public class ElasticSearchServer extends SearchServer {

    private static final Logger log = LoggerFactory.getLogger(ElasticSearchServer.class);
    private static final Logger elasticClientLogger = LoggerFactory.getLogger(log.getName() + "#elasticSearchClient");

    private ServiceProvider serviceProviderClass;
    private final ElasticVindClient elasticSearchClient;

    public ElasticSearchServer() {
        // this is mainly used with the ServiceLoader infrastructure
        this(getElasticServerProvider() != null ? getElasticServerProvider().getInstance() : null);
        serviceProviderClass = getElasticServerProvider();
    }

    /**
     * Creates an instance of ElasticSearch server performing ping and the schema validity check.
     * @param client ElasticClient to connect to.
     */
    public ElasticSearchServer(ElasticVindClient client) {
        this(client, true);
    }

    /**
     * Creates an instance of ElasticSearch server allowing to avoid the schema validity check.
     * @param client ElasticClient to connect to.
     * @param check true to perform local schema validity check against remote schema, false otherwise.
     */
    protected ElasticSearchServer(ElasticVindClient client, boolean check) {

        elasticSearchClient = client;

        //In order to perform unit tests with mocked ElasticClient, we do not need to do the schema check.
        if(check && client != null) {
            try {
                if (elasticSearchClient.ping()) {
                    log.debug("Successful ping to Elasticsearch server");
                } else {
                    log.error("Cannot connect to Elasticsearch server: ping failed");
                    throw new SearchServerException("Cannot connect to Elasticsearch server: ping failed");
                }
            } catch ( IOException e) {
                log.error("Cannot connect to Elasticsearch server: ping failed");
                throw new SearchServerException("Cannot connect to Elasticsearch server: ping failed", e);
            }
            log.info("Connection to Elastic server successful");

            try {
                if(!client.indexExists()) {
                    if(SearchConfiguration.get(SearchConfiguration.SERVER_COLLECTION_AUTOCREATE, false)) {
                        try {
                            log.info("AutoGenerate elastic collection {}", client.getDefaultIndex());
                            client.createIndex(client.getDefaultIndex());
                        } catch (IOException e) {
                            throw new SearchServerException(
                                    String.format(
                                            "Error when creating collection %s: %s", client.getDefaultIndex(), e.getMessage()
                                    ), e
                            );
                        }

                        log.info("Collection {} created successfully", client.getDefaultIndex());
                    } else {
                        throw new SearchServerException("Index does not exists, try to enable auto-generation");
                    }
                }
            } catch (IOException e) {
                throw new SearchServerException("Cannot connect to Elasticsearch server: index check failed", e);
            }

            try {
                checkVersionAndMappings();
            } catch (IOException e) {
                log.error("Elasticsearch server Schema validation error: {}", e.getMessage(), e);
                throw new SearchServerException(String.format("Elastic search Schema validation error: %s", e.getMessage()), e);
            }

        } else {
            log.warn("Elastic ping and schema validity check has been deactivated.");
        }
    }

    private void checkVersionAndMappings() throws IOException {

         final TypeReference<HashMap<String, Object>> typeRef =
                 new TypeReference<HashMap<String, Object>>() {};
        final Map<String, Object> localMappings = new ObjectMapper().readValue(ElasticMappingUtils.getDefaultMapping(), typeRef);
        final Map<String, Object> remoteMappings = elasticSearchClient.getMappings().mappings().get(elasticSearchClient.getDefaultIndex()).getSourceAsMap();

        ElasticMappingUtils.checkMappingsCompatibility(localMappings,remoteMappings,elasticSearchClient.getDefaultIndex());
    }

    @Override
    public Object getBackend() {
        return elasticSearchClient;
    }

    @Override
    public StatusResult getBackendStatus() {
        try {
            if(elasticSearchClient.ping()) {
                return StatusResult.up().setDetail("status", 0);
            } else {
                return StatusResult.down().setDetail("status", 1);
            }

        } catch ( IOException e) {
            log.error("Cannot connect to Elasticsearch server: ping failed");
            throw new SearchServerException("Cannot connect to Elasticsearch server: ping failed", e);
        }
    }

    @Override
    public IndexResult index(Document... docs) {
        Asserts.notNull(docs,"Document to index should not be null.");
        Asserts.check(docs.length > 0, "Should be at least one document to index.");
        return indexMultipleDocuments(Arrays.asList(docs), -1);
    }

    @Override
    public IndexResult index(List<Document> docs) {
        Asserts.notNull(docs,"Document to index should not be null.");
        Asserts.check(!docs.isEmpty(), "Should be at least one document to index.");

        return  indexMultipleDocuments(docs, -1);
    }

    @Override
    public IndexResult indexWithin(Document doc, int withinMs) {
        Asserts.notNull(doc,"Document to index should not be null.");
        log.warn("Parameter 'within' not in use in elastic search backend");
        return indexMultipleDocuments(Collections.singletonList(doc), withinMs);
    }

    @Override
    public IndexResult indexWithin(List<Document> docs, int withinMs) {
        Asserts.notNull(docs,"Document to index should not be null.");
        Asserts.check(!docs.isEmpty(), "Should be at least one document to index.");
        log.warn("Parameter 'within' not in use in elastic search backend");
        return  indexMultipleDocuments(docs, withinMs);
    }

    @Override
    public DeleteResult delete(Document doc) {
        return deleteWithin(doc, -1);
    }

    @Override
    public DeleteResult deleteWithin(Document doc, int withinMs) {
        log.warn("Parameter 'within' not in use in elastic search backend");
        try {
            final StopWatch elapsedTime = StopWatch.createStarted();
            elasticClientLogger.debug(">>> delete({})", doc.getId());
            final DeleteResponse deleteResponse = elasticSearchClient.deleteById(doc.getId());
            elapsedTime.stop();
            return new DeleteResult(elapsedTime.getTime()).setElapsedTime(elapsedTime.getTime());
        } catch (ElasticsearchException | IOException e) {
            log.error("Cannot delete document {}", doc.getId() , e);
            throw new SearchServerException("Cannot delete document", e);
        }
    }

    @Override
    public boolean execute(Update update, DocumentFactory factory) {
        try {
            log.warn("Update script builder does not check for script injection. Ensure values provided are script safe.");
            final StopWatch elapsedTime = StopWatch.createStarted();
            elasticClientLogger.debug(">>> delete({})", update);
            final PainlessScript.ScriptBuilder updateScript = ElasticQueryBuilder.buildUpdateScript(update.getOptions(), factory, update.getUpdateContext());
            final UpdateResponse response = elasticSearchClient.update(update.getId(), updateScript);
            elapsedTime.stop();
            return true;
        } catch (ElasticsearchException | IOException e) {
            log.error("Cannot update document {}: {}", update.getId(), e.getMessage() , e);
            throw new SearchServerException(
                    String.format("Cannot update document %s: %s", update.getId(), e.getMessage()), e);
        }
    }

    @Override
    public DeleteResult execute(Delete delete, DocumentFactory factory) {
        try {
            final StopWatch elapsedTime = StopWatch.createStarted();
            elasticClientLogger.debug(">>> delete({})", delete);
            final QueryBuilder deleteQuery = ElasticQueryBuilder.buildFilterQuery(delete.getQuery(), factory, delete.getUpdateContext());
            final BulkByScrollResponse response = elasticSearchClient.deleteByQuery(deleteQuery);
            elapsedTime.stop();
            return new DeleteResult(response.getTook().getMillis()).setElapsedTime(elapsedTime.getTime());
        } catch (ElasticsearchException | IOException e) {
            log.error("Cannot delete with query {}", delete.getQuery() , e);
            throw new SearchServerException(
                    String.format("Cannot delete with query %s", delete.getQuery().toString()), e);
        }
    }

    @Override
    public void commit(boolean optimize) {
        log.warn("Commit does not have any effect on elastic search backend");
    }

    @Override
    public <T> BeanSearchResult<T> execute(FulltextSearch search, Class<T> c) {
        final DocumentFactory factory = AnnotationUtil.createDocumentFactory(c);
        final SearchResult docResult = this.execute(search, factory);
        return docResult.toPojoResult(docResult, c);
    }

    @Override
    public SearchResult execute(FulltextSearch search, DocumentFactory factory) {
        final StopWatch elapsedtime = StopWatch.createStarted();
        final SearchSourceBuilder query = ElasticQueryBuilder.buildQuery(search, factory);

        //query
        try {
            elasticClientLogger.debug(">>> query({})", query.toString());
            final SearchResponse response = elasticSearchClient.query(query);
            if(Objects.nonNull(response)
                    && Objects.nonNull(response.getHits())
                    && Objects.nonNull(response.getHits().getHits())){
                //TODO: if nested doc search is implemented
                //final Map<String,Integer> childCounts = SolrUtils.getChildCounts(response);

                final List<Document> documents = Arrays.stream(response.getHits().getHits())
                        .map(hit -> DocumentUtil.buildVindDoc(hit, factory, search.getSearchContext()))
                        .collect(Collectors.toList());

                String spellcheckText = null;
                if ( search.isSpellcheck() && CollectionUtils.isEmpty(documents)) {

                    //if no results, try spellchecker (if defined and if spellchecked query differs from original)
                    final List<String> spellCheckedQuery = ElasticQueryBuilder.getSpellCheckedQuery(response);

                    //query with checked query

                    if(spellCheckedQuery != null && CollectionUtils.isNotEmpty(spellCheckedQuery)) {
                        final Iterator<String> iterator = spellCheckedQuery.iterator();
                        while(iterator.hasNext()) {
                            final String text = iterator.next();
                            final SearchSourceBuilder spellcheckQuery =
                                    ElasticQueryBuilder.buildQuery(search.text(text).spellcheck(false), factory);
                            final SearchResponse spellcheckResponse = elasticSearchClient.query(spellcheckQuery);
                            documents.addAll(Arrays.stream(spellcheckResponse.getHits().getHits())
                                    .map(hit -> DocumentUtil.buildVindDoc(hit, factory, search.getSearchContext()))
                                    .collect(Collectors.toList()));
                        }
                    }
                }

                // Building Vind Facet Results
                final FacetResults facetResults =
                        ResultUtils.buildFacetResults(
                                response.getAggregations(),
                                factory,
                                search.getFacets(),
                                search.getSearchContext());

                elapsedtime.stop();

                final long totalHits = response.getHits().getTotalHits().value;
                switch(search.getResultSet().getType()) {
                    case page:{
                        return new PageResult(totalHits, response.getTook().getMillis(), documents, search, facetResults, this, factory).setElapsedTime(elapsedtime.getTime());
                    }
                    case slice: {
                        return new SliceResult(totalHits, response.getTook().getMillis(), documents, search, facetResults, this, factory).setElapsedTime(elapsedtime.getTime());
                    }
                    default:
                        return new PageResult(totalHits, response.getTook().getMillis(), documents, search, facetResults, this, factory).setElapsedTime(elapsedtime.getTime());
                }
            }else {
                throw new ElasticsearchException("Empty result from ElasticClient");
            }

        } catch (ElasticsearchException | IOException e) {
            throw new SearchServerException(String.format("Cannot issue query: %s",e.getMessage()), e);
        }
    }

    @Override
    public String getRawQuery(FulltextSearch search, DocumentFactory factory) {
        //TODO implement for monitoring search server;
        return "";
    }

    @Override
    public <T> String getRawQuery(FulltextSearch search, Class<T> c) {
        throw new NotImplementedException();
    }

    @Override
    public <T> SuggestionResult execute(ExecutableSuggestionSearch search, Class<T> c) {
        throw new NotImplementedException();
    }

    @Override
    public SuggestionResult execute(ExecutableSuggestionSearch search, DocumentFactory factory) {
        final StopWatch elapsedtime = StopWatch.createStarted();
        final SearchSourceBuilder query = ElasticQueryBuilder.buildSuggestionQuery(search, factory);
        //query
        try {
            elasticClientLogger.debug(">>> query({})", query.toString());
            final SearchResponse response = elasticSearchClient.query(query);
            HashMap<FieldDescriptor, TermFacetResult<?>> suggestionValues =
                    ResultUtils.buildSuggestionResults(response, factory, search.getSearchContext());

            String spellcheckText = null;
            if (!suggestionValues.values().stream()
                    .anyMatch(termFacetResult -> CollectionUtils.isNotEmpty(termFacetResult.getValues())) ) {

                //if no results, try spellchecker (if defined and if spellchecked query differs from original)
                final List<String> spellCheckedQuery = ElasticQueryBuilder.getSpellCheckedQuery(response);

                //query with checked query

                if(spellCheckedQuery != null && CollectionUtils.isNotEmpty(spellCheckedQuery)) {
                    final Iterator<String> iterator = spellCheckedQuery.iterator();
                    while(iterator.hasNext()) {
                        final String text = iterator.next();
                        final SearchSourceBuilder spellcheckQuery = ElasticQueryBuilder.buildSuggestionQuery(search.text(text), factory);
                        final SearchResponse spellcheckResponse = elasticSearchClient.query(spellcheckQuery);
                        final HashMap<FieldDescriptor, TermFacetResult<?>> spellcheckValues =
                                ResultUtils.buildSuggestionResults(spellcheckResponse, factory, search.getSearchContext());
                        if (spellcheckValues.values().stream()
                                .anyMatch(termFacetResult -> CollectionUtils.isNotEmpty(termFacetResult.getValues())) ) {
                            spellcheckText = text;
                            suggestionValues = spellcheckValues;
                            break;
                        }
                    }
                }
            }

            elapsedtime.stop();
            return new SuggestionResult(
                    suggestionValues,
                    spellcheckText,
                    response.getTook().getMillis(),
                    factory)
                    .setElapsedTime(elapsedtime.getTime(TimeUnit.MILLISECONDS));

        } catch (ElasticsearchException | IOException e) {
            throw new SearchServerException(String.format("Cannot issue query: %s",e.getMessage()), e);
        }
    }

    @Override
    public SuggestionResult execute(ExecutableSuggestionSearch search, DocumentFactory assets, DocumentFactory childFactory) {
        throw new NotImplementedException();
    }

    @Override
    public String getRawQuery(ExecutableSuggestionSearch search, DocumentFactory factory) {
        //TODO implement for monitoring search server;
        return "";
    }

    @Override
    public String getRawQuery(ExecutableSuggestionSearch search, DocumentFactory factory, DocumentFactory childFactory) {
        throw new NotImplementedException();
    }

    @Override
    public <T> String getRawQuery(ExecutableSuggestionSearch search, Class<T> c) {
        throw new NotImplementedException();
    }

    @Override
    public <T> BeanGetResult<T> execute(RealTimeGet search, Class<T> c) {
        final DocumentFactory documentFactory = AnnotationUtil.createDocumentFactory(c);
        final GetResult result = this.execute(search, documentFactory);
        return result.toPojoResult(result,c);
    }

    @Override
    public GetResult execute(RealTimeGet search, DocumentFactory assets) {
        try {
            final StopWatch elapsedTime = StopWatch.createStarted();
            final MultiGetResponse response = elasticSearchClient.realTimeGet(search.getValues());
            elapsedTime.stop();

            if(response!=null){
                return ResultUtils.buildRealTimeGetResult(response, search, assets, elapsedTime.getTime()).setElapsedTime(elapsedTime.getTime());
            }else {
                log.error("Null result from ElasticClient");
                throw new SearchServerException("Null result from ElasticClient");
            }

        } catch (ElasticsearchException | IOException e) {
            log.error("Cannot execute realTime get query");
            throw new SearchServerException("Cannot execute realTime get query", e);
        }
    }

    @Override
    public void clearIndex() {
        try {
            elasticClientLogger.debug(">>> clear complete index");
            elasticSearchClient.deleteByQuery(QueryBuilders.matchAllQuery());
        } catch (ElasticsearchException | IOException e) {
            log.error("Cannot clear index", e);
            throw new SearchServerException("Cannot clear index", e);
        }
    }

    @Override
    public void close() {
        if (elasticSearchClient != null) try {
            elasticSearchClient.close();
        } catch (IOException e) {
            log.error("Cannot close search server", e);
            throw new SearchServerException("Cannot close search server", e);
        }
    }

    @Override
    public Class<? extends ServiceProvider> getServiceProviderClass() {
        return ElasticServerProvider.class;
    }

    private static ElasticServerProvider getElasticServerProvider() {
        final String providerClassName = SearchConfiguration.get(SearchConfiguration.SERVER_PROVIDER, null);

        final ServiceLoader<ElasticServerProvider> loader = ServiceLoader.load(ElasticServerProvider.class);
        final Iterator<ElasticServerProvider> it = loader.iterator();

        ElasticServerProvider serverProvider = null;
        if(providerClassName == null) {
            if (!it.hasNext()) {
                log.error("No ElasticServerProvider in classpath");
                throw new RuntimeException("No ElasticServerProvider in classpath");
            } else {
                serverProvider = it.next();
            }
            if (it.hasNext()) {
                log.warn("Multiple bindings for ElasticServerProvider found: {}", loader.iterator());
            }
        } else {
            try {
                final Class<?> providerClass = Class.forName(providerClassName);

                while(it.hasNext()) {
                    final ElasticServerProvider p = it.next();
                    if(providerClass.isAssignableFrom(p.getClass())) {
                        serverProvider = p;
                        break;
                    }
                }
                if(Objects.isNull(serverProvider)) {
                    log.info("No server provider of type class {} found in classpath for server {}",
                            providerClassName, ElasticServerProvider.class.getCanonicalName());
                }
            } catch (ClassNotFoundException e) {
                log.warn("Specified class {} is not in classpath",providerClassName, e);
                //throw new RuntimeException("Specified class " + providerClassName + " is not in classpath");
            }
        }

        return serverProvider;
    }

    private IndexResult indexSingleDocument(Document doc, int withinMs) {
        log.warn("Parameter 'within' not in use in elastic search backend");
        final StopWatch elapsedTime = StopWatch.createStarted();
        final Map<String,Object> document = DocumentUtil.createInputDocument(doc);

        try {
            if (elasticClientLogger.isTraceEnabled()) {
                elasticClientLogger.debug(">>> add({})", doc.getId());
            } else {
                elasticClientLogger.debug(">>> add({})", doc.getId());
            }

            final BulkResponse response = this.elasticSearchClient.add(document);
            elapsedTime.stop();
            return new IndexResult(response.getTook().getMillis()).setElapsedTime(elapsedTime.getTime());

        } catch (ElasticsearchException | IOException e) {
            log.error("Cannot index document {}", document.get(FieldUtil.ID) , e);
            throw new SearchServerException("Cannot index document", e);
        }
    }

    private IndexResult indexMultipleDocuments(List<Document> docs, int withinMs) {
        log.warn("Parameter 'within' not in use in elastic search backend");
        final StopWatch elapsedTime = StopWatch.createStarted();
        final List<Map<String,Object>> jsonDocs = docs.parallelStream()
                .map(DocumentUtil::createInputDocument)
                .collect(Collectors.toList());
        try {
            if (elasticClientLogger.isTraceEnabled()) {
                elasticClientLogger.debug(">>> add({})", jsonDocs);
            } else {
                elasticClientLogger.debug(">>> add({})", jsonDocs);
            }

            final BulkResponse response =this.elasticSearchClient.add(jsonDocs) ;
            elapsedTime.stop();
            return new IndexResult(elapsedTime.getTime()).setElapsedTime(elapsedTime.getTime());

        } catch (ElasticsearchException | IOException e) {
            log.error("Cannot index documents {}", jsonDocs, e);
            throw new SearchServerException("Cannot index documents", e);
        }
    }
}

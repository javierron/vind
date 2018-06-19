/*
 * Copyright (c) 2018 Redlink GmbH.
 */
package com.rbmhtechnology.vind.monitoring.report;

import com.google.gson.JsonObject;
import com.rbmhtechnology.vind.monitoring.report.configuration.ElasticSearchConnectionConfiguration;
import com.rbmhtechnology.vind.monitoring.report.configuration.ElasticSearchReportConfiguration;
import com.rbmhtechnology.vind.monitoring.report.preprocess.ElasticSearchReportPreprocessor;
import com.rbmhtechnology.vind.monitoring.report.service.ElasticSearchReportService;
import com.rbmhtechnology.vind.monitoring.report.writer.HtmlReportWriter;
import org.junit.*;

import java.io.File;
import java.io.IOException;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;

/**
 * Created on 08.06.18.
 */
public class createReportIT {

    /*
    private final String esHost = "localhost";
    private final String esPort = "9200";
    private final String esIndex = "logindex";
    private final String esEntryType = "logEntry";
    private final String applicationName = "Application name - 0.0.0";
    private final String messageWrapper = "";
    private ElasticSearchReportService esRepsortService;
    */
    private ElasticSearchReportService esRepsortService;

    private final ElasticSearchReportConfiguration config = new ElasticSearchReportConfiguration()
            .setApplicationId("mediamanager-Media Manager Content Management-Assets")
            .setEsEntryType("logback")
            .setMessageWrapper("message_json")
            .setConnectionConfiguration(new ElasticSearchConnectionConfiguration(
                    "172.20.30.95",
                    "19200",
                    "logstash-searchanalysis-2018.*"
            ));

    private Report report;
    private HtmlReportWriter reportWriter =  new HtmlReportWriter();
    private File reportFile;
    private ElasticSearchReportPreprocessor reportPreprocessor;
    private ZonedDateTime from;
    private ZonedDateTime to;


    @Before
    public void setUp() throws IOException {
        from = ZonedDateTime.now().minusMonths(1);
        to   = ZonedDateTime.now().minusMonths(1).plusDays(5);

        reportPreprocessor = new ElasticSearchReportPreprocessor(config, from, to);

        esRepsortService = new ElasticSearchReportService(config, from, to);

        reportFile = File.createTempFile("reportTest", ".html");

    }

    @Test
    @Ignore
    public void preprocessIT() {
        reportPreprocessor
                .addSystemFilterField("static_partitionID")
                .preprocess();

    }

    @Test
    //@Ignore
    public void createReportIT() {
        esRepsortService.preprocessData("static_partitionID");
        final LinkedHashMap<String, JsonObject> topFaceFields = esRepsortService.getTopFaceFields();
        final ArrayList<String> facetFields = new ArrayList<>(topFaceFields.keySet());

        final LinkedHashMap<String, Long> topSuggestionFields = esRepsortService.getTopSuggestionFields();
        final ArrayList<String> suggestFields = new ArrayList<>(topSuggestionFields.keySet());
        this.report = new Report()
                .setApplicationName(esRepsortService.getConfiguration().getApplicationId())
                .setFrom(from)
                .setTo(to)
                .setTopDays(esRepsortService.getTopDays())
                .setRequests(esRepsortService.getTotalRequests())
                .setTopSuggestionFields(topSuggestionFields)
                .setSuggestionFieldsValues(esRepsortService.getSuggestionFieldsValues(suggestFields))
                .setFacetFieldsValues(esRepsortService.getFacetFieldsValues(facetFields))
                .setTopFacetFields(topFaceFields)
                .setTopQueries(esRepsortService.getTopQueries())
                .setTopUsers(esRepsortService.getTopUsers());
        Assert.assertTrue(reportWriter.write(this.report,reportFile.getAbsolutePath()));

        System.out.println("Report has been written to " + reportFile.getAbsolutePath());

    }

    @After
    public void cleanUp() throws Exception {
        esRepsortService.close();

        if (reportFile.exists()) {
            reportFile.delete();
        }
    }


}

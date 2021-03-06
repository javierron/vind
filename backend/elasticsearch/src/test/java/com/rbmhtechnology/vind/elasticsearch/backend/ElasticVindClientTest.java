package com.rbmhtechnology.vind.elasticsearch.backend;

import com.rbmhtechnology.vind.elasticsearch.backend.util.FieldUtil;
import org.elasticsearch.action.bulk.BulkResponse;
import org.junit.Test;

import java.io.IOException;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class ElasticVindClientTest  extends ElasticBaseTest{

    @Test
    public void testPing() throws IOException {
        assertTrue(client.ping());
    }

    @Test
    public void testAdd() throws IOException {

        final Map<String, Object> doc = new HashMap<>();
        doc.put("dynamic_string_title", "The last ascent of man");
        doc.put(FieldUtil.ID, "AA-2X3451");
        doc.put(FieldUtil.TYPE, "TestDoc");
        final BulkResponse indexResult = client.add(doc);
        assertNotNull(indexResult);
        assertEquals("CREATED", indexResult.getItems()[0].status().name());
    }

    @Test
    public void testAddMultipleDocuments() throws IOException {

        final Map<String, Object> doc1 = new HashMap<>();
        doc1.put("dynamic_string_title", "Dawn of humanity: the COVID-19 chronicles");
        doc1.put(FieldUtil.ID, "AA-2X6891");
        doc1.put(FieldUtil.TYPE, "TestDoc");

        final Map<String, Object> doc2 = new HashMap<>();
        doc2.put("dynamic_string_title", "The last ascend of man");
        doc2.put(FieldUtil.ID, "AA-2X3451");
        doc2.put(FieldUtil.TYPE, "TestDoc");
        final BulkResponse indexResult = client.add(Arrays.asList(doc1,doc2));
        assertNotNull(indexResult);
        assertFalse(indexResult.hasFailures());
    }

    @Test
    public void testAddBinaryField() throws IOException {

        final Map<String, Object> doc = new HashMap<>();
        doc.put("dynamic_binary_raw_data", Base64.getEncoder().encode("The last ascent of man".getBytes()));
        doc.put(FieldUtil.ID, "AA-2X3451");
        doc.put(FieldUtil.TYPE, "TestDoc");
        final BulkResponse indexResult = client.add(doc);
        assertNotNull(indexResult);
        assertEquals("OK", indexResult.getItems()[0].status().name());
    }
}

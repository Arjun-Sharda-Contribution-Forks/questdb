/*******************************************************************************
 *  _  _ ___ ___     _ _
 * | \| | __/ __| __| | |__
 * | .` | _|\__ \/ _` | '_ \
 * |_|\_|_| |___/\__,_|_.__/
 *
 * Copyright (c) 2014-2016. The NFSdb project and its contributors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 ******************************************************************************/

package com.nfsdb.net.http.handlers;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.nfsdb.JournalEntryWriter;
import com.nfsdb.JournalWriter;
import com.nfsdb.ex.JournalException;
import com.nfsdb.ex.NumericException;
import com.nfsdb.factory.JournalFactoryPool;
import com.nfsdb.factory.configuration.JournalStructure;
import com.nfsdb.misc.Dates;
import com.nfsdb.misc.Files;
import com.nfsdb.misc.Rnd;
import com.nfsdb.net.http.HttpServer;
import com.nfsdb.net.http.HttpServerConfiguration;
import com.nfsdb.net.http.QueryResponse;
import com.nfsdb.net.http.SimpleUrlMatcher;
import com.nfsdb.ql.parser.AbstractOptimiserTest;
import com.nfsdb.test.tools.HttpTestUtils;
import org.junit.*;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.net.URLEncoder;
import java.sql.Timestamp;

public class QueryHandlerTest extends AbstractOptimiserTest {

    @ClassRule
    public static final TemporaryFolder temp = new TemporaryFolder();
    private static JournalFactoryPool factoryPool;
    private static HttpServer server;
    private static QueryHandler handler;

    @BeforeClass
    public static void setUp() throws Exception {
        factoryPool = new JournalFactoryPool(factory.getConfiguration(), 1);
        handler = new QueryHandler(factoryPool);

        server = new HttpServer(new HttpServerConfiguration(), new SimpleUrlMatcher() {{
            put("/js", handler);
        }});

        server.start();
        generateJournal();
    }

    @AfterClass
    public static void tearDown() throws Exception {
        server.halt();
        factoryPool.close();
    }

    @Test
    public void testJsonChunkOverflow() throws Exception {
        int count = 10000;
        generateJournal("large", count);
        QueryResponse queryResponse = download("large");
        Assert.assertEquals(count, queryResponse.result.length);
    }

    @Test
    public void testJsonEmpty() throws Exception {
        QueryResponse queryResponse = download("tab", 0, 0, false);
        Assert.assertEquals(0, queryResponse.result.length);
    }

    @Test
    public void testJsonEmpty0() throws Exception {
        QueryResponse queryResponse = download("tab where 1 = 2", 0, 0, false);
        Assert.assertEquals(0, queryResponse.result.length);
    }

    @Test
    public void testJsonEmptyQuery() throws Exception {
        Assert.assertNull(download("", 0, 0, false).result);
    }

    @Test
    public void testJsonEncodeControlChars() throws Exception {
        java.lang.StringBuilder allChars = new java.lang.StringBuilder();
        for (char c = Character.MIN_VALUE; c < 0xD800; c++) { //
            allChars.append(c);
        }

        String allCharString = allChars.toString();
        generateJournal("xyz", allCharString, 1.900232E-10, 2.598E20, Long.MAX_VALUE, Integer.MIN_VALUE, new Timestamp(0));
        String query = "select id from xyz \n limit 1";
        QueryResponse queryResponse = download(query);
        Assert.assertEquals(query, queryResponse.query);
        for (int i = 0; i < allCharString.length(); i++) {
            Assert.assertTrue("result len is less than " + i, i < queryResponse.result[0].id.length());
            Assert.assertEquals(i + "", allCharString.charAt(i), queryResponse.result[0].id.charAt(i));
        }
    }

    @Test
    public void testJsonEncodeNumbers() throws Exception {
        generateJournal("nums", null, 1.900232E-10, Double.MAX_VALUE, Long.MAX_VALUE, Integer.MIN_VALUE, new Timestamp(10));
        QueryResponse queryResponse = download("nums limit 20");
        Assert.assertEquals(1.900232E-10, queryResponse.result[0].x, 1E-6);
        Assert.assertEquals(Double.MAX_VALUE, queryResponse.result[0].y, 1E-6);
        Assert.assertEquals(Long.MAX_VALUE, queryResponse.result[0].z);
        Assert.assertEquals(0, queryResponse.result[0].w);
//        Assert.assertEquals(ts, queryResponse.result[0].timestamp);
        Assert.assertEquals(false, queryResponse.moreExist);

        Assert.assertEquals("id4", queryResponse.result[4].id);
        Assert.assertTrue(Double.isNaN(queryResponse.result[4].y));
    }

    @Test
    public void testJsonInvertedLimit() throws Exception {
        String query = "tab limit 10";
        QueryResponse queryResponse = download(query, 10, 5, false);
        Assert.assertEquals(0, queryResponse.result.length);
        Assert.assertEquals(true, queryResponse.moreExist);
    }

    @Test
    public void testJsonLimits() throws Exception {
        String query = "tab";
        QueryResponse r = download(query, 2, 4, false);
        Assert.assertEquals(2, r.result.length);
        Assert.assertEquals(true, r.moreExist);
        Assert.assertEquals("id2", r.result[0].id);
        Assert.assertEquals("id3", r.result[1].id);
    }

    @Test
    public void testJsonPooling() throws Exception {
        QueryResponse queryResponse1 = download("tab limit 10");
        QueryResponse queryResponse2 = download("tab limit 10");
        QueryResponse queryResponse3 = download("tab limit 10");
        QueryResponse queryResponse4 = download("tab limit 10");

        Assert.assertEquals(10, queryResponse1.result.length);
        Assert.assertEquals(10, queryResponse2.result.length);
        Assert.assertEquals(10, queryResponse3.result.length);
        Assert.assertEquals(10, queryResponse4.result.length);

        Assert.assertTrue(handler.getCacheHits() > 0);
        Assert.assertTrue(handler.getCacheMisses() > 0);
    }

    @Test
    public void testJsonSimple() throws Exception {
        QueryResponse queryResponse = download("select 1 z from tab limit 10");
        Assert.assertEquals(10, queryResponse.result.length);
    }

    @Test
    public void testJsonTakeLimit() throws Exception {
        QueryResponse queryResponse = download("tab limit 10", 2, -1, false);
        Assert.assertEquals(2, queryResponse.result.length);
        Assert.assertEquals(true, queryResponse.moreExist);
    }

    @Test
    public void testJsonTotalCount() throws Exception {
        String query = "tab";
        QueryResponse r = download(query, 2, 4, true);
        Assert.assertEquals(2, r.result.length);
        Assert.assertEquals(false, r.moreExist);
        Assert.assertEquals("id2", r.result[0].id);
        Assert.assertEquals("id3", r.result[1].id);
        Assert.assertEquals(1000, r.totalCount);
    }

    static QueryResponse download(String queryUrl, TemporaryFolder temp) throws Exception {
        return download(queryUrl, -1, -1, false, temp);
    }

    static void generateJournal(String name, int count) throws JournalException, NumericException {
        generateJournal(name, new QueryResponse.Tab[0], count);
    }

    static void generateJournal(String name, String id, double x, double y, long z, int w, Timestamp timestamp) throws JournalException, NumericException {
        QueryResponse.Tab record = new QueryResponse.Tab();
        record.id = id;
        record.x = x;
        record.y = y;
        record.z = z;
        record.w = w;
        record.timestamp = timestamp;
        generateJournal(name, new QueryResponse.Tab[]{record}, 1000);
    }

    private static QueryResponse download(String queryUrl, int limitFrom, int limitTo, boolean count, TemporaryFolder temp) throws Exception {
        File f = temp.newFile();
        String url = "http://localhost:9000/js?query=" + URLEncoder.encode(queryUrl, "UTF-8");
        if (limitFrom >= 0) {
            url += "&limit=" + limitFrom;
        }
        if (limitTo >= 0) {
            url += "," + limitTo;
        }

        if (count) {
            url += "&withCount=true";
        }

        HttpTestUtils.download(HttpTestUtils.clientBuilder(false), url, f);
        Gson gson = new GsonBuilder().setDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").create();
        return gson.fromJson(Files.readStringFromFile(f), QueryResponse.class);
    }

    private static void generateJournal() throws JournalException, NumericException {
        generateJournal("tab", new QueryResponse.Tab[0], 1000);
    }

    private static void generateJournal(String name, QueryResponse.Tab[] recs, int count) throws JournalException, NumericException {
        try (JournalWriter w = factory.writer(
                new JournalStructure(name).
                        $sym("id").
                        $double("x").
                        $double("y").
                        $long("z").
                        $int("w").
                        $ts()

        )) {

            Rnd rnd = new Rnd();
            long t = Dates.parseDateTime("2015-03-12T00:00:00.000Z");

            for (int i = 0; i < count; i++) {
                JournalEntryWriter ew = w.entryWriter();
                ew.putSym(0, recs.length > i ? recs[i].id : "id" + i);
                ew.putDouble(1, recs.length > i ? recs[i].x : rnd.nextDouble());
                if (recs.length > i) {
                    ew.putDouble(2, recs[i].y);
                    ew.putLong(3, recs[i].z);
                } else {
                    if (rnd.nextPositiveInt() % 10 == 0) {
                        ew.putNull(2);
                    } else {
                        ew.putDouble(2, rnd.nextDouble());
                    }
                    if (rnd.nextPositiveInt() % 10 == 0) {
                        ew.putNull(3);
                    } else {
                        ew.putLong(3, rnd.nextLong() % 500);
                    }
                }
                ew.putInt(4, recs.length > i ? recs[i].w : rnd.nextInt() % 500);
                ew.putDate(5, recs.length > i ? recs[i].timestamp.getTime() : t);
                t += 10;
                ew.append();
            }
            w.commit();
        }
    }

    private static QueryResponse download(String queryUrl) throws Exception {
        return download(queryUrl, temp);
    }

    private static QueryResponse download(String queryUrl, int limitFrom, int limitTo, boolean count) throws Exception {
        return download(queryUrl, limitFrom, limitTo, count, temp);
    }
}
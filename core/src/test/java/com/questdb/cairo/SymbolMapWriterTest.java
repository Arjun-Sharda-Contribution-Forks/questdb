/*******************************************************************************
 *    ___                  _   ____  ____
 *   / _ \ _   _  ___  ___| |_|  _ \| __ )
 *  | | | | | | |/ _ \/ __| __| | | |  _ \
 *  | |_| | |_| |  __/\__ \ |_| |_| | |_) |
 *   \__\_\\__,_|\___||___/\__|____/|____/
 *
 * Copyright (C) 2014-2017 Appsicle
 *
 * This program is free software: you can redistribute it and/or  modify
 * it under the terms of the GNU Affero General Public License, version 3,
 * as published by the Free Software Foundation.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 *
 ******************************************************************************/

package com.questdb.cairo;

import com.questdb.common.SymbolTable;
import com.questdb.std.Chars;
import com.questdb.std.Files;
import com.questdb.std.ObjList;
import com.questdb.std.Rnd;
import com.questdb.std.str.Path;
import com.questdb.test.tools.TestUtils;
import org.junit.Assert;
import org.junit.Test;

public class SymbolMapWriterTest extends AbstractCairoTest {

    @Test
    public void testAppend() throws Exception {
        TestUtils.assertMemoryLeak(() -> {
            int N = 1000;
            try (Path path = new Path().of(configuration.getRoot())) {
                SymbolMapWriter.create(configuration, path, "x", N);
                Rnd rnd = new Rnd();

                try (SymbolMapWriter writer = new SymbolMapWriter(configuration, path, "x", true, 0)) {
                    long prev = -1L;
                    for (int i = 0; i < N; i++) {
                        CharSequence cs = rnd.nextChars(10);
                        long key = writer.put(cs);
                        Assert.assertEquals(prev + 1, key);
                        Assert.assertEquals(key, writer.put(cs));
                        prev = key;
                    }
                }

                try (SymbolMapWriter writer = new SymbolMapWriter(configuration, path, "x", true, N)) {
                    long prev = N - 1;
                    // append second batch and check that symbol keys start with N
                    for (int i = 0; i < N; i++) {
                        CharSequence cs = rnd.nextChars(10);
                        long key = writer.put(cs);
                        Assert.assertEquals(prev + 1, key);
                        Assert.assertEquals(key, writer.put(cs));
                        prev = key;
                    }

                    // try append first batch - this should return symbol keys starting with 0
                    rnd.reset();
                    prev = -1;
                    for (int i = 0; i < N; i++) {
                        CharSequence cs = rnd.nextChars(10);
                        long key = writer.put(cs);
                        Assert.assertEquals(prev + 1, key);
                        prev = key;
                    }
                    Assert.assertEquals(SymbolTable.VALUE_IS_NULL, writer.put(null));
                }
            }
        });
    }

//    @Test
//    public void testLookupPerformanceOld() throws JournalException {
//        int N = 100000000;
//        int symbolCount = 1024;
//        ObjList<String> symbols = new ObjList<>();
//        MMappedSymbolTable tab = new MMappedSymbolTable(symbolCount, 256, 1, new File(configuration.getRoot().toString()), "x", JournalMode.APPEND, 0, 0, false, true);
//        Rnd rnd = new Rnd();
//        long prev = -1L;
//        for (int i = 0; i < symbolCount; i++) {
//            CharSequence cs = rnd.nextChars(10);
//            long key = tab.put(cs);
//            symbols.add(cs.toString());
//            Assert.assertEquals(prev + 1, key);
//            prev = key;
//        }
//
//        long t = System.nanoTime();
//        for (int i = 0; i < N; i++) {
//            int key = rnd.nextPositiveInt() % symbolCount;
//            Assert.assertEquals(key, tab.put(symbols.getQuick(key)));
//        }
//        System.out.println(System.nanoTime() - t);
//    }

    @Test
    public void testLookupPerformance() throws Exception {
        TestUtils.assertMemoryLeak(() -> {
            int N = 10000000;
            int symbolCount = 1024;
            ObjList<String> symbols = new ObjList<>();
            try (Path path = new Path().of(configuration.getRoot())) {
                SymbolMapWriter.create(configuration, path, "x", symbolCount);
                try (SymbolMapWriter writer = new SymbolMapWriter(configuration, path, "x", true, 0)) {
                    Rnd rnd = new Rnd();
                    long prev = -1L;
                    for (int i = 0; i < symbolCount; i++) {
                        CharSequence cs = rnd.nextChars(10);
                        long key = writer.put(cs);
                        symbols.add(cs.toString());
                        Assert.assertEquals(prev + 1, key);
                        prev = key;
                    }

                    long t = System.nanoTime();
                    for (int i = 0; i < N; i++) {
                        int key = rnd.nextPositiveInt() % symbolCount;
                        Assert.assertEquals(key, writer.put(symbols.getQuick(key)));
                    }
                    System.out.println("SymbolMapWriter lookup performance [10M <500ms]: " + (System.nanoTime() - t) / 1000000);
                }
            }
        });
    }

    @Test
    public void testMapDoesNotExist() throws Exception {
        TestUtils.assertMemoryLeak(() -> {
            try (Path path = new Path().of(configuration.getRoot())) {
                try {
                    new SymbolMapWriter(configuration, path, "x", false, 0);
                    Assert.fail();
                } catch (CairoException e) {
                    Assert.assertTrue(Chars.contains(e.getMessage(), "does not exist"));
                }
            }
        });
    }

    @Test
    public void testRollback() throws Exception {
        TestUtils.assertMemoryLeak(() -> {
            int N = 1024;
            try (Path path = new Path().of(configuration.getRoot())) {
                SymbolMapWriter.create(configuration, path, "x", N);
                try (SymbolMapWriter writer = new SymbolMapWriter(configuration, path, "x", true, 0)) {
                    Rnd rnd = new Rnd();
                    long prev = -1L;
                    for (int i = 0; i < N; i++) {
                        CharSequence cs = rnd.nextChars(10);
                        long key = writer.put(cs);
                        Assert.assertEquals(prev + 1, key);
                        Assert.assertEquals(key, writer.put(cs));
                        prev = key;
                    }

                    writer.rollback(N / 2);

                    prev = N / 2 - 1;
                    for (int i = 0; i < N; i++) {
                        CharSequence cs = rnd.nextChars(10);
                        long key = writer.put(cs);
                        Assert.assertEquals(prev + 1, key);
                        Assert.assertEquals(key, writer.put(cs));
                        prev = key;
                    }

                }
            }
        });
    }

    @Test
    public void testShortHeader() throws Exception {
        TestUtils.assertMemoryLeak(() -> {
            try (Path path = new Path().of(configuration.getRoot())) {
                int plen = path.length();
                Assert.assertTrue(Files.touch(path.concat("x").put(".o").$()));
                try {
                    new SymbolMapWriter(configuration, path.trimTo(plen), "x", false, 0);
                    Assert.fail();
                } catch (CairoException e) {
                    Assert.assertTrue(Chars.contains(e.getMessage(), "too short"));
                }
            }
        });
    }

    @Test
    public void testSimpleAdd() throws Exception {
        TestUtils.assertMemoryLeak(() -> {
            int N = 1000000;
            try (Path path = new Path().of(configuration.getRoot())) {
                SymbolMapWriter.create(configuration, path, "x", N);
                try (SymbolMapWriter writer = new SymbolMapWriter(configuration, path, "x", false, 0)) {
                    Rnd rnd = new Rnd();
                    long prev = -1L;
                    for (int i = 0; i < N; i++) {
                        CharSequence cs = rnd.nextChars(10);
                        long key = writer.put(cs);
                        Assert.assertEquals(prev + 1, key);
                        Assert.assertEquals(key, writer.put(cs));
                        prev = key;
                    }
                }
            }
        });
    }

    @Test
    public void testSimpleRead() throws Exception {
        TestUtils.assertMemoryLeak(() -> {
            int N = 1000000;
            Rnd rnd = new Rnd();
            try (Path path = new Path().of(configuration.getRoot())) {
                SymbolMapWriter.create(configuration, path, "x", N);
                try (SymbolMapWriter writer = new SymbolMapWriter(configuration, path, "x", false, 0)) {
                    long prev = -1L;
                    for (int i = 0; i < N; i++) {
                        CharSequence cs = rnd.nextChars(10);
                        long key = writer.put(cs);
                        Assert.assertEquals(prev + 1, key);
                        prev = key;
                    }
                }
                rnd.reset();
                try (SymbolMapReader reader = new SymbolMapReader(configuration, path, "x", N)) {
                    for (int i = 0; i < N; i++) {
                        CharSequence cs = rnd.nextChars(10);
                        TestUtils.assertEquals(cs, reader.value(i));
                        Assert.assertEquals(i, reader.getQuick(cs));
                    }

                    Assert.assertNull(reader.value(-1));

                    try {
                        reader.value(N + 1);
                        Assert.fail();
                    } catch (CairoException e) {
                        Assert.assertTrue(Chars.contains(e.getMessage(), "Invalid key"));
                    }
                }
            }
        });
    }
}
/*
 * Licensed to Elasticsearch under one or more contributor
 * license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright
 * ownership. Elasticsearch licenses this file to you under
 * the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.elasticsearch.index.store;

import org.apache.lucene.analysis.MockAnalyzer;
import org.apache.lucene.codecs.CodecUtil;
import org.apache.lucene.codecs.FilterCodec;
import org.apache.lucene.codecs.SegmentInfoFormat;
import org.apache.lucene.codecs.lucene50.Lucene50Codec;
import org.apache.lucene.codecs.lucene50.Lucene50SegmentInfoFormat;
import org.apache.lucene.document.*;
import org.apache.lucene.index.*;
import org.apache.lucene.store.*;
import org.apache.lucene.util.BytesRef;
import org.apache.lucene.util.IOUtils;
import org.apache.lucene.util.TestUtil;
import org.apache.lucene.util.Version;
import org.elasticsearch.ExceptionsHelper;
import org.elasticsearch.common.settings.ImmutableSettings;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.unit.TimeValue;
import org.elasticsearch.env.ShardLock;
import org.elasticsearch.index.Index;
import org.elasticsearch.index.shard.ShardId;
import org.elasticsearch.index.store.distributor.Distributor;
import org.elasticsearch.index.store.distributor.LeastUsedDistributor;
import org.elasticsearch.index.store.distributor.RandomWeightedDistributor;
import org.elasticsearch.test.DummyShardLock;
import org.elasticsearch.test.ElasticsearchLuceneTestCase;
import org.hamcrest.Matchers;
import org.junit.Test;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.file.NoSuchFileException;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.Adler32;

import static com.carrotsearch.randomizedtesting.RandomizedTest.*;
import static org.hamcrest.Matchers.*;

public class StoreTest extends ElasticsearchLuceneTestCase {

    @Test
    public void testRefCount() throws IOException {
        final ShardId shardId = new ShardId(new Index("index"), 1);
        DirectoryService directoryService = new LuceneManagedDirectoryService(random());
        Store store = new Store(shardId, ImmutableSettings.EMPTY, directoryService, randomDistributor(directoryService), new DummyShardLock(shardId));
        int incs = randomIntBetween(1, 100);
        for (int i = 0; i < incs; i++) {
            if (randomBoolean()) {
                store.incRef();
            } else {
                assertTrue(store.tryIncRef());
            }
            store.ensureOpen();
        }

        for (int i = 0; i < incs; i++) {
            store.decRef();
            store.ensureOpen();
        }

        store.incRef();
        final AtomicBoolean called = new AtomicBoolean(false);
        store.close();
        for (int i = 0; i < incs; i++) {
            if (randomBoolean()) {
                store.incRef();
            } else {
                assertTrue(store.tryIncRef());
            }
            store.ensureOpen();
        }

        for (int i = 0; i < incs; i++) {
            store.decRef();
            store.ensureOpen();
        }

        store.decRef();
        assertThat(store.refCount(), Matchers.equalTo(0));
        assertFalse(store.tryIncRef());
        try {
            store.incRef();
            fail(" expected exception");
        } catch (AlreadyClosedException ex) {

        }
        try {
            store.ensureOpen();
            fail(" expected exception");
        } catch (AlreadyClosedException ex) {

        }
    }

    @Test
    public void testVerifyingIndexOutput() throws IOException {
        Directory dir = newDirectory();
        IndexOutput output = dir.createOutput("foo.bar", IOContext.DEFAULT);
        int iters = scaledRandomIntBetween(10, 100);
        for (int i = 0; i < iters; i++) {
            BytesRef bytesRef = new BytesRef(TestUtil.randomRealisticUnicodeString(random(), 10, 1024));
            output.writeBytes(bytesRef.bytes, bytesRef.offset, bytesRef.length);
        }
        CodecUtil.writeFooter(output);
        output.close();
        IndexInput indexInput = dir.openInput("foo.bar", IOContext.DEFAULT);
        String checksum = Store.digestToString(CodecUtil.retrieveChecksum(indexInput));
        indexInput.seek(0);
        BytesRef ref = new BytesRef(scaledRandomIntBetween(1, 1024));
        long length = indexInput.length();
        IndexOutput verifyingOutput = new Store.LuceneVerifyingIndexOutput(new StoreFileMetaData("foo1.bar", length, checksum), dir.createOutput("foo1.bar", IOContext.DEFAULT));
        while (length > 0) {
            if (random().nextInt(10) == 0) {
                verifyingOutput.writeByte(indexInput.readByte());
                length--;
            } else {
                int min = (int) Math.min(length, ref.bytes.length);
                indexInput.readBytes(ref.bytes, ref.offset, min);
                verifyingOutput.writeBytes(ref.bytes, ref.offset, min);
                length -= min;
            }
        }
        Store.verify(verifyingOutput);
        verifyingOutput.writeByte((byte) 0x0);
        try {
            Store.verify(verifyingOutput);
            fail("should be a corrupted index");
        } catch (CorruptIndexException | IndexFormatTooOldException | IndexFormatTooNewException ex) {
            // ok
        }
        IOUtils.close(indexInput, verifyingOutput, dir);
    }

    @Test
    public void testVerifyingIndexOutputWithBogusInput() throws IOException {
        Directory dir = newDirectory();
        int length = scaledRandomIntBetween(10, 1024);
        IndexOutput verifyingOutput = new Store.LuceneVerifyingIndexOutput(new StoreFileMetaData("foo1.bar", length, ""), dir.createOutput("foo1.bar", IOContext.DEFAULT));
        try {
            while (length > 0) {
                verifyingOutput.writeByte((byte) random().nextInt());
                length--;
            }
            fail("should be a corrupted index");
        } catch (CorruptIndexException | IndexFormatTooOldException | IndexFormatTooNewException ex) {
            // ok
        }
        IOUtils.close(verifyingOutput, dir);
    }

    // TODO: remove this, its too fragile. just use a static old index instead.
    private static final class OldSIMockingCodec extends FilterCodec {

        protected OldSIMockingCodec() {
            super(new Lucene50Codec().getName(), new Lucene50Codec());
        }

        @Override
        public SegmentInfoFormat segmentInfoFormat() {
            final SegmentInfoFormat segmentInfoFormat = super.segmentInfoFormat();
            return new SegmentInfoFormat() {
                @Override
                public SegmentInfo read(Directory directory, String segmentName, byte[] segmentID, IOContext context) throws IOException {
                    return segmentInfoFormat.read(directory, segmentName, segmentID, context);
                }
                // this sucks it's a full copy of Lucene50SegmentInfoFormat but hey I couldn't find a way to make it write 4_5_0 versions
                // somebody was too paranoid when implementing this. ey rmuir, was that you? - go fix it :P
                @Override
                public void write(Directory dir, SegmentInfo si, IOContext ioContext) throws IOException {
                    final String fileName = IndexFileNames.segmentFileName(si.name, "", Lucene50SegmentInfoFormat.SI_EXTENSION);
                    si.addFile(fileName);

                    boolean success = false;
                    try (IndexOutput output = dir.createOutput(fileName, ioContext)) {
                        CodecUtil.writeIndexHeader(output,
                                "Lucene50SegmentInfo",
                                0,
                                si.getId(),
                                "");
                        Version version = Version.LUCENE_4_5_0; // FOOOOOO!!
                        // Write the Lucene version that created this segment, since 3.1
                        output.writeInt(version.major);
                        output.writeInt(version.minor);
                        output.writeInt(version.bugfix);
                        assert version.prerelease == 0;
                        output.writeInt(si.getDocCount());

                        output.writeByte((byte) (si.getUseCompoundFile() ? SegmentInfo.YES : SegmentInfo.NO));
                        output.writeStringStringMap(si.getDiagnostics());
                        Set<String> files = si.files();
                        for (String file : files) {
                            if (!IndexFileNames.parseSegmentName(file).equals(si.name)) {
                                throw new IllegalArgumentException("invalid files: expected segment=" + si.name + ", got=" + files);
                            }
                        }
                        output.writeStringSet(files);
                        output.writeStringStringMap(si.getAttributes());
                        CodecUtil.writeFooter(output);
                        success = true;
                    } finally {
                        if (!success) {
                            // TODO: are we doing this outside of the tracking wrapper? why must SIWriter cleanup like this?
                            IOUtils.deleteFilesIgnoringExceptions(si.dir, fileName);
                        }
                    }
                }
            };
        }
    }

    // IF THIS TEST FAILS ON UPGRADE GO LOOK AT THE OldSIMockingCodec!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!
    @Test
    public void testWriteLegacyChecksums() throws IOException {
        final ShardId shardId = new ShardId(new Index("index"), 1);
        DirectoryService directoryService = new LuceneManagedDirectoryService(random());
        Store store = new Store(shardId, ImmutableSettings.EMPTY, directoryService, randomDistributor(directoryService), new DummyShardLock(shardId));
        // set default codec - all segments need checksums
        final boolean usesOldCodec = randomBoolean();
        IndexWriter writer = new IndexWriter(store.directory(), newIndexWriterConfig(random(), new MockAnalyzer(random())).setCodec(usesOldCodec ? new OldSIMockingCodec() : actualDefaultCodec()));
        int docs = 1 + random().nextInt(100);

        for (int i = 0; i < docs; i++) {
            Document doc = new Document();
            doc.add(new TextField("id", "" + i, random().nextBoolean() ? Field.Store.YES : Field.Store.NO));
            doc.add(new TextField("body", TestUtil.randomRealisticUnicodeString(random()), random().nextBoolean() ? Field.Store.YES : Field.Store.NO));
            doc.add(new SortedDocValuesField("dv", new BytesRef(TestUtil.randomRealisticUnicodeString(random()))));
            writer.addDocument(doc);
        }
        if (random().nextBoolean()) {
            for (int i = 0; i < docs; i++) {
                if (random().nextBoolean()) {
                    Document doc = new Document();
                    doc.add(new TextField("id", "" + i, random().nextBoolean() ? Field.Store.YES : Field.Store.NO));
                    doc.add(new TextField("body", TestUtil.randomRealisticUnicodeString(random()), random().nextBoolean() ? Field.Store.YES : Field.Store.NO));
                    writer.updateDocument(new Term("id", "" + i), doc);
                }
            }
        }
        if (random().nextBoolean()) {
            DirectoryReader.open(writer, random().nextBoolean()).close(); // flush
        }
        Store.MetadataSnapshot metadata;
        // check before we committed
        try {
            store.getMetadata();
            fail("no index present - expected exception");
        } catch (IndexNotFoundException ex) {
            // expected
        }
        assertThat(store.getMetadataOrEmpty(), is(Store.MetadataSnapshot.EMPTY)); // nothing committed

        writer.close();
        Store.LegacyChecksums checksums = new Store.LegacyChecksums();
        Map<String, StoreFileMetaData> legacyMeta = new HashMap<>();
        for (String file : store.directory().listAll()) {
            if (file.equals("write.lock") || file.equals(IndexFileNames.OLD_SEGMENTS_GEN)) {
                continue;
            }
            BytesRef hash = new BytesRef();
            if (file.startsWith("segments")) {
                hash = Store.MetadataSnapshot.hashFile(store.directory(), file);
            }
            StoreFileMetaData storeFileMetaData = new StoreFileMetaData(file, store.directory().fileLength(file), file + "checksum", null, hash);
            legacyMeta.put(file, storeFileMetaData);
            checksums.add(storeFileMetaData);
        }
        checksums.write(store);

        metadata = store.getMetadata();
        Map<String, StoreFileMetaData> stringStoreFileMetaDataMap = metadata.asMap();
        assertThat(legacyMeta.size(), equalTo(stringStoreFileMetaDataMap.size()));
        if (usesOldCodec) {
            for (StoreFileMetaData meta : legacyMeta.values()) {
                assertTrue(meta.toString(), stringStoreFileMetaDataMap.containsKey(meta.name()));
                assertEquals(meta.name() + "checksum", meta.checksum());
                assertTrue(meta + " vs. " + stringStoreFileMetaDataMap.get(meta.name()), stringStoreFileMetaDataMap.get(meta.name()).isSame(meta));
            }
        } else {

            // even if we have a legacy checksum - if we use a new codec we should reuse
            for (StoreFileMetaData meta : legacyMeta.values()) {
                assertTrue(meta.toString(), stringStoreFileMetaDataMap.containsKey(meta.name()));
                assertFalse(meta + " vs. " + stringStoreFileMetaDataMap.get(meta.name()), stringStoreFileMetaDataMap.get(meta.name()).isSame(meta));
                StoreFileMetaData storeFileMetaData = metadata.get(meta.name());
                try (IndexInput input = store.openVerifyingInput(meta.name(), IOContext.DEFAULT, storeFileMetaData)) {
                    assertTrue(storeFileMetaData.toString(), input instanceof Store.VerifyingIndexInput);
                    input.seek(meta.length());
                    Store.verify(input);
                }
            }
        }
        assertDeleteContent(store, directoryService);
        IOUtils.close(store);

    }

    @Test
    public void testNewChecksums() throws IOException {
        final ShardId shardId = new ShardId(new Index("index"), 1);
        DirectoryService directoryService = new LuceneManagedDirectoryService(random());
        Store store = new Store(shardId, ImmutableSettings.EMPTY, directoryService, randomDistributor(directoryService), new DummyShardLock(shardId));
        // set default codec - all segments need checksums
        IndexWriter writer = new IndexWriter(store.directory(), newIndexWriterConfig(random(), new MockAnalyzer(random())).setCodec(actualDefaultCodec()));
        int docs = 1 + random().nextInt(100);

        for (int i = 0; i < docs; i++) {
            Document doc = new Document();
            doc.add(new TextField("id", "" + i, random().nextBoolean() ? Field.Store.YES : Field.Store.NO));
            doc.add(new TextField("body", TestUtil.randomRealisticUnicodeString(random()), random().nextBoolean() ? Field.Store.YES : Field.Store.NO));
            doc.add(new SortedDocValuesField("dv", new BytesRef(TestUtil.randomRealisticUnicodeString(random()))));
            writer.addDocument(doc);
        }
        if (random().nextBoolean()) {
            for (int i = 0; i < docs; i++) {
                if (random().nextBoolean()) {
                    Document doc = new Document();
                    doc.add(new TextField("id", "" + i, random().nextBoolean() ? Field.Store.YES : Field.Store.NO));
                    doc.add(new TextField("body", TestUtil.randomRealisticUnicodeString(random()), random().nextBoolean() ? Field.Store.YES : Field.Store.NO));
                    writer.updateDocument(new Term("id", "" + i), doc);
                }
            }
        }
        if (random().nextBoolean()) {
            DirectoryReader.open(writer, random().nextBoolean()).close(); // flush
        }
        Store.MetadataSnapshot metadata;
        // check before we committed
        try {
            store.getMetadata();
            fail("no index present - expected exception");
        } catch (IndexNotFoundException ex) {
            // expected
        }
        assertThat(store.getMetadataOrEmpty(), is(Store.MetadataSnapshot.EMPTY)); // nothing committed
        writer.commit();
        writer.close();
        metadata = store.getMetadata();
        assertThat(metadata.asMap().isEmpty(), is(false));
        for (StoreFileMetaData meta : metadata) {
            try (IndexInput input = store.directory().openInput(meta.name(), IOContext.DEFAULT)) {
                String checksum = Store.digestToString(CodecUtil.retrieveChecksum(input));
                assertThat("File: " + meta.name() + " has a different checksum", meta.checksum(), equalTo(checksum));
                assertThat(meta.hasLegacyChecksum(), equalTo(false));
                assertThat(meta.writtenBy(), equalTo(Version.LATEST));
                if (meta.name().endsWith(".si") || meta.name().startsWith("segments_")) {
                    assertThat(meta.hash().length, greaterThan(0));
                }
            }
        }
        assertConsistent(store, metadata);

        TestUtil.checkIndex(store.directory());
        assertDeleteContent(store, directoryService);
        IOUtils.close(store);
    }

    @Test
    public void testMixedChecksums() throws IOException {
        final ShardId shardId = new ShardId(new Index("index"), 1);
        DirectoryService directoryService = new LuceneManagedDirectoryService(random());
        Store store = new Store(shardId, ImmutableSettings.EMPTY, directoryService, randomDistributor(directoryService), new DummyShardLock(shardId));
        // this time random codec....
        IndexWriter writer = new IndexWriter(store.directory(), newIndexWriterConfig(random(), new MockAnalyzer(random())).setCodec(actualDefaultCodec()));
        int docs = 1 + random().nextInt(100);

        for (int i = 0; i < docs; i++) {
            Document doc = new Document();
            doc.add(new TextField("id", "" + i, random().nextBoolean() ? Field.Store.YES : Field.Store.NO));
            doc.add(new TextField("body", TestUtil.randomRealisticUnicodeString(random()), random().nextBoolean() ? Field.Store.YES : Field.Store.NO));
            doc.add(new SortedDocValuesField("dv", new BytesRef(TestUtil.randomRealisticUnicodeString(random()))));
            writer.addDocument(doc);
        }
        if (random().nextBoolean()) {
            for (int i = 0; i < docs; i++) {
                if (random().nextBoolean()) {
                    Document doc = new Document();
                    doc.add(new TextField("id", "" + i, random().nextBoolean() ? Field.Store.YES : Field.Store.NO));
                    doc.add(new TextField("body", TestUtil.randomRealisticUnicodeString(random()), random().nextBoolean() ? Field.Store.YES : Field.Store.NO));
                    writer.updateDocument(new Term("id", "" + i), doc);
                }
            }
        }
        if (random().nextBoolean()) {
            DirectoryReader.open(writer, random().nextBoolean()).close(); // flush
        }
        Store.MetadataSnapshot metadata;
        // check before we committed
        try {
            store.getMetadata();
            fail("no index present - expected exception");
        } catch (IndexNotFoundException ex) {
            // expected
        }
        assertThat(store.getMetadataOrEmpty(), is(Store.MetadataSnapshot.EMPTY)); // nothing committed
        writer.commit();
        writer.close();
        Store.LegacyChecksums checksums = new Store.LegacyChecksums();
        metadata = store.getMetadata();
        assertThat(metadata.asMap().isEmpty(), is(false));
        for (StoreFileMetaData meta : metadata) {
            try (IndexInput input = store.directory().openInput(meta.name(), IOContext.DEFAULT)) {
                if (meta.checksum() == null) {
                    String checksum = null;
                    try {
                        CodecUtil.retrieveChecksum(input);
                        fail("expected a corrupt index - posting format has not checksums");
                    } catch (CorruptIndexException | IndexFormatTooOldException | IndexFormatTooNewException ex) {
                        try (ChecksumIndexInput checksumIndexInput = store.directory().openChecksumInput(meta.name(), IOContext.DEFAULT)) {
                            checksumIndexInput.seek(meta.length());
                            checksum = Store.digestToString(checksumIndexInput.getChecksum());
                        }
                        // fine - it's a postings format without checksums
                        checksums.add(new StoreFileMetaData(meta.name(), meta.length(), checksum, null));
                    }
                } else {
                    String checksum = Store.digestToString(CodecUtil.retrieveChecksum(input));
                    assertThat("File: " + meta.name() + " has a different checksum", meta.checksum(), equalTo(checksum));
                    assertThat(meta.hasLegacyChecksum(), equalTo(false));
                    assertThat(meta.writtenBy(), equalTo(Version.LATEST));
                }
            }
        }
        assertConsistent(store, metadata);
        checksums.write(store);
        metadata = store.getMetadata();
        assertThat(metadata.asMap().isEmpty(), is(false));
        for (StoreFileMetaData meta : metadata) {
            assertThat("file: " + meta.name() + " has a null checksum", meta.checksum(), not(nullValue()));
            if (meta.hasLegacyChecksum()) {
                try (ChecksumIndexInput checksumIndexInput = store.directory().openChecksumInput(meta.name(), IOContext.DEFAULT)) {
                    checksumIndexInput.seek(meta.length());
                    assertThat(meta.checksum(), equalTo(Store.digestToString(checksumIndexInput.getChecksum())));
                }
            } else {
                try (IndexInput input = store.directory().openInput(meta.name(), IOContext.DEFAULT)) {
                    String checksum = Store.digestToString(CodecUtil.retrieveChecksum(input));
                    assertThat("File: " + meta.name() + " has a different checksum", meta.checksum(), equalTo(checksum));
                    assertThat(meta.hasLegacyChecksum(), equalTo(false));
                    assertThat(meta.writtenBy(), equalTo(Version.LATEST));
                }
            }
        }
        assertConsistent(store, metadata);
        TestUtil.checkIndex(store.directory());
        assertDeleteContent(store, directoryService);
        IOUtils.close(store);
    }

    @Test
    public void testRenameFile() throws IOException {
        final ShardId shardId = new ShardId(new Index("index"), 1);
        DirectoryService directoryService = new LuceneManagedDirectoryService(random(), false);
        Store store = new Store(shardId, ImmutableSettings.EMPTY, directoryService, randomDistributor(directoryService), new DummyShardLock(shardId));
        {
            IndexOutput output = store.directory().createOutput("foo.bar", IOContext.DEFAULT);
            int iters = scaledRandomIntBetween(10, 100);
            for (int i = 0; i < iters; i++) {
                BytesRef bytesRef = new BytesRef(TestUtil.randomRealisticUnicodeString(random(), 10, 1024));
                output.writeBytes(bytesRef.bytes, bytesRef.offset, bytesRef.length);
            }
            CodecUtil.writeFooter(output);
            output.close();
        }
        store.renameFile("foo.bar", "bar.foo");
        assertThat(store.directory().listAll().length, is(1));
        final long lastChecksum;
        try (IndexInput input = store.directory().openInput("bar.foo", IOContext.DEFAULT)) {
            lastChecksum = CodecUtil.checksumEntireFile(input);
        }

        try {
            store.directory().openInput("foo.bar", IOContext.DEFAULT);
            fail("file was renamed");
        } catch (FileNotFoundException | NoSuchFileException ex) {
            // expected
        }
        {
            IndexOutput output = store.directory().createOutput("foo.bar", IOContext.DEFAULT);
            int iters = scaledRandomIntBetween(10, 100);
            for (int i = 0; i < iters; i++) {
                BytesRef bytesRef = new BytesRef(TestUtil.randomRealisticUnicodeString(random(), 10, 1024));
                output.writeBytes(bytesRef.bytes, bytesRef.offset, bytesRef.length);
            }
            CodecUtil.writeFooter(output);
            output.close();
        }
        DistributorDirectory distributorDirectory = DirectoryUtils.getLeaf(store.directory(), DistributorDirectory.class);
        if (distributorDirectory != null && distributorDirectory.getDirectory("foo.bar") != distributorDirectory.getDirectory("bar.foo")) {
            try {
                store.renameFile("foo.bar", "bar.foo");
                fail("target file already exists in a different directory");
            } catch (IOException ex) {
                // expected
            }

            try (IndexInput input = store.directory().openInput("bar.foo", IOContext.DEFAULT)) {
                assertThat(lastChecksum, equalTo(CodecUtil.checksumEntireFile(input)));
            }
            assertThat(store.directory().listAll().length, is(2));
            assertDeleteContent(store, directoryService);
            IOUtils.close(store);
        } else {
            store.renameFile("foo.bar", "bar.foo");
            assertThat(store.directory().listAll().length, is(1));
            assertDeleteContent(store, directoryService);
            IOUtils.close(store);
        }
    }

    public void testCheckIntegrity() throws IOException {
        Directory dir = newDirectory();
        long luceneFileLength = 0;

        try (IndexOutput output = dir.createOutput("lucene_checksum.bin", IOContext.DEFAULT)) {
            int iters = scaledRandomIntBetween(10, 100);
            for (int i = 0; i < iters; i++) {
                BytesRef bytesRef = new BytesRef(TestUtil.randomRealisticUnicodeString(random(), 10, 1024));
                output.writeBytes(bytesRef.bytes, bytesRef.offset, bytesRef.length);
                luceneFileLength += bytesRef.length;
            }
            CodecUtil.writeFooter(output);
            luceneFileLength += CodecUtil.footerLength();

        }

        final Adler32 adler32 = new Adler32();
        long legacyFileLength = 0;
        try (IndexOutput output = dir.createOutput("legacy.bin", IOContext.DEFAULT)) {
            int iters = scaledRandomIntBetween(10, 100);
            for (int i = 0; i < iters; i++) {
                BytesRef bytesRef = new BytesRef(TestUtil.randomRealisticUnicodeString(random(), 10, 1024));
                output.writeBytes(bytesRef.bytes, bytesRef.offset, bytesRef.length);
                adler32.update(bytesRef.bytes, bytesRef.offset, bytesRef.length);
                legacyFileLength += bytesRef.length;
            }
        }
        final long luceneChecksum;
        final long adler32LegacyChecksum = adler32.getValue();
        try(IndexInput indexInput = dir.openInput("lucene_checksum.bin", IOContext.DEFAULT)) {
            assertEquals(luceneFileLength, indexInput.length());
            luceneChecksum = CodecUtil.retrieveChecksum(indexInput);
        }

        { // positive check
            StoreFileMetaData lucene = new StoreFileMetaData("lucene_checksum.bin", luceneFileLength, Store.digestToString(luceneChecksum), Version.LUCENE_4_8_0);
            StoreFileMetaData legacy = new StoreFileMetaData("legacy.bin", legacyFileLength, Store.digestToString(adler32LegacyChecksum));
            assertTrue(legacy.hasLegacyChecksum());
            assertFalse(lucene.hasLegacyChecksum());
            assertTrue(Store.checkIntegrityNoException(lucene, dir));
            assertTrue(Store.checkIntegrityNoException(legacy, dir));
        }

        { // negative check - wrong checksum
            StoreFileMetaData lucene = new StoreFileMetaData("lucene_checksum.bin", luceneFileLength, Store.digestToString(luceneChecksum+1), Version.LUCENE_4_8_0);
            StoreFileMetaData legacy = new StoreFileMetaData("legacy.bin", legacyFileLength, Store.digestToString(adler32LegacyChecksum+1));
            assertTrue(legacy.hasLegacyChecksum());
            assertFalse(lucene.hasLegacyChecksum());
            assertFalse(Store.checkIntegrityNoException(lucene, dir));
            assertFalse(Store.checkIntegrityNoException(legacy, dir));
        }

        { // negative check - wrong length
            StoreFileMetaData lucene = new StoreFileMetaData("lucene_checksum.bin", luceneFileLength+1, Store.digestToString(luceneChecksum), Version.LUCENE_4_8_0);
            StoreFileMetaData legacy = new StoreFileMetaData("legacy.bin", legacyFileLength+1, Store.digestToString(adler32LegacyChecksum));
            assertTrue(legacy.hasLegacyChecksum());
            assertFalse(lucene.hasLegacyChecksum());
            assertFalse(Store.checkIntegrityNoException(lucene, dir));
            assertFalse(Store.checkIntegrityNoException(legacy, dir));
        }

        { // negative check - wrong file
            StoreFileMetaData lucene = new StoreFileMetaData("legacy.bin", luceneFileLength, Store.digestToString(luceneChecksum), Version.LUCENE_4_8_0);
            StoreFileMetaData legacy = new StoreFileMetaData("lucene_checksum.bin", legacyFileLength, Store.digestToString(adler32LegacyChecksum));
            assertTrue(legacy.hasLegacyChecksum());
            assertFalse(lucene.hasLegacyChecksum());
            assertFalse(Store.checkIntegrityNoException(lucene, dir));
            assertFalse(Store.checkIntegrityNoException(legacy, dir));
        }
        dir.close();

    }

    @Test
    public void testVerifyingIndexInput() throws IOException {
        Directory dir = newDirectory();
        IndexOutput output = dir.createOutput("foo.bar", IOContext.DEFAULT);
        int iters = scaledRandomIntBetween(10, 100);
        for (int i = 0; i < iters; i++) {
            BytesRef bytesRef = new BytesRef(TestUtil.randomRealisticUnicodeString(random(), 10, 1024));
            output.writeBytes(bytesRef.bytes, bytesRef.offset, bytesRef.length);
        }
        CodecUtil.writeFooter(output);
        output.close();

        // Check file
        IndexInput indexInput = dir.openInput("foo.bar", IOContext.DEFAULT);
        long checksum = CodecUtil.retrieveChecksum(indexInput);
        indexInput.seek(0);
        IndexInput verifyingIndexInput = new Store.VerifyingIndexInput(dir.openInput("foo.bar", IOContext.DEFAULT));
        readIndexInputFullyWithRandomSeeks(verifyingIndexInput);
        Store.verify(verifyingIndexInput);
        assertThat(checksum, equalTo(((ChecksumIndexInput) verifyingIndexInput).getChecksum()));
        IOUtils.close(indexInput, verifyingIndexInput);

        // Corrupt file and check again
        corruptFile(dir, "foo.bar", "foo1.bar");
        verifyingIndexInput = new Store.VerifyingIndexInput(dir.openInput("foo1.bar", IOContext.DEFAULT));
        readIndexInputFullyWithRandomSeeks(verifyingIndexInput);
        try {
            Store.verify(verifyingIndexInput);
            fail("should be a corrupted index");
        } catch (CorruptIndexException | IndexFormatTooOldException | IndexFormatTooNewException ex) {
            // ok
        }
        IOUtils.close(verifyingIndexInput);
        IOUtils.close(dir);
    }

    private void readIndexInputFullyWithRandomSeeks(IndexInput indexInput) throws IOException{
        BytesRef ref = new BytesRef(scaledRandomIntBetween(1, 1024));
        long pos = 0;
        while (pos < indexInput.length()) {
            assertEquals(pos, indexInput.getFilePointer());
            int op = random().nextInt(5);
            if (op == 0 ) {
                int shift =  100 - randomIntBetween(0, 200);
                pos =  Math.min(indexInput.length() - 1, Math.max(0, pos + shift));
                indexInput.seek(pos);
            } else if (op == 1) {
                indexInput.readByte();
                pos ++;
            } else {
                int min = (int) Math.min(indexInput.length() - pos, ref.bytes.length);
                indexInput.readBytes(ref.bytes, ref.offset, min);
                pos += min;
            }
        }
    }

    private void corruptFile(Directory dir, String fileIn, String fileOut) throws IOException {
        IndexInput input = dir.openInput(fileIn, IOContext.READONCE);
        IndexOutput output = dir.createOutput(fileOut, IOContext.DEFAULT);
        long len = input.length();
        byte[] b = new byte[1024];
        long broken = randomInt((int) len);
        long pos = 0;
        while (pos < len) {
            int min = (int) Math.min(input.length() - pos, b.length);
            input.readBytes(b, 0, min);
            if (broken >= pos && broken < pos + min) {
                // Flip one byte
                int flipPos = (int) (broken - pos);
                b[flipPos] = (byte) (b[flipPos] ^ 42);
            }
            output.writeBytes(b, min);
            pos += min;
        }
        IOUtils.close(input, output);

    }

    public void assertDeleteContent(Store store, DirectoryService service) throws IOException {
        deleteContent(store.directory());
        assertThat(Arrays.toString(store.directory().listAll()), store.directory().listAll().length, equalTo(0));
        assertThat(store.stats().sizeInBytes(), equalTo(0l));
        for (Directory dir : service.build()) {
            assertThat(dir.listAll().length, equalTo(0));
        }
    }

    private static final class LuceneManagedDirectoryService extends DirectoryService {
        private final Directory[] dirs;
        private final Random random;

        public LuceneManagedDirectoryService(Random random) {
            this(random, true);
        }
        public LuceneManagedDirectoryService(Random random, boolean preventDoubleWrite) {
            super(new ShardId("fake", 1), ImmutableSettings.EMPTY);
            this.dirs = new Directory[1 + random.nextInt(5)];
            for (int i = 0; i < dirs.length; i++) {
                dirs[i]  = newDirectory(random);
                if (dirs[i] instanceof MockDirectoryWrapper) {
                    ((MockDirectoryWrapper)dirs[i]).setPreventDoubleWrite(preventDoubleWrite);
                    // TODO: fix this test to handle virus checker
                    ((MockDirectoryWrapper)dirs[i]).setEnableVirusScanner(false);
                }
            }
            this.random = random;
        }
        @Override
        public Directory[] build() throws IOException {
            return dirs;
        }

        @Override
        public long throttleTimeInNanos() {
            return random.nextInt(1000);
        }
    }

    public static void assertConsistent(Store store, Store.MetadataSnapshot metadata) throws IOException {
        for (String file : store.directory().listAll()) {
            if (!IndexWriter.WRITE_LOCK_NAME.equals(file) && !IndexFileNames.OLD_SEGMENTS_GEN.equals(file) && !Store.isChecksum(file)) {
                assertTrue(file + " is not in the map: " + metadata.asMap().size() + " vs. " + store.directory().listAll().length, metadata.asMap().containsKey(file));
            } else {
                assertFalse(file + " is not in the map: " + metadata.asMap().size() + " vs. " + store.directory().listAll().length, metadata.asMap().containsKey(file));
            }
        }
    }
    private Distributor randomDistributor(DirectoryService service) throws IOException {
        return randomDistributor(random(), service);
    }

    private Distributor randomDistributor(Random random, DirectoryService service) throws IOException {
        return random.nextBoolean() ? new LeastUsedDistributor(service) : new RandomWeightedDistributor(service);
    }

    /**
     * Legacy indices without lucene CRC32 did never write or calculate checksums for segments_N files
     * but for other files
     */
    @Test
    public void testRecoveryDiffWithLegacyCommit() {
        Map<String, StoreFileMetaData> metaDataMap = new HashMap<>();
        metaDataMap.put("segments_1", new StoreFileMetaData("segments_1", 50, null, null, new BytesRef(new byte[] {1})));
        metaDataMap.put("_0_1.del", new StoreFileMetaData("_0_1.del", 42, "foobarbaz", null, new BytesRef()));
        Store.MetadataSnapshot first = new Store.MetadataSnapshot(metaDataMap);

        Store.MetadataSnapshot second = new Store.MetadataSnapshot(metaDataMap);
        Store.RecoveryDiff recoveryDiff = first.recoveryDiff(second);
        assertEquals(recoveryDiff.toString(), recoveryDiff.different.size(), 2);
    }


    @Test
    public void testRecoveryDiff() throws IOException, InterruptedException {
        int numDocs = 2 + random().nextInt(100);
        List<Document> docs = new ArrayList<>();
        for (int i = 0; i < numDocs; i++) {
            Document doc = new Document();
            doc.add(new StringField("id", "" + i, random().nextBoolean() ? Field.Store.YES : Field.Store.NO));
            doc.add(new TextField("body", TestUtil.randomRealisticUnicodeString(random()), random().nextBoolean() ? Field.Store.YES : Field.Store.NO));
            doc.add(new SortedDocValuesField("dv", new BytesRef(TestUtil.randomRealisticUnicodeString(random()))));
            docs.add(doc);
        }
        long seed = random().nextLong();
        Store.MetadataSnapshot first;
        {
            Random random = new Random(seed);
            IndexWriterConfig iwc = new IndexWriterConfig(new MockAnalyzer(random)).setCodec(actualDefaultCodec());
            iwc.setMergePolicy(NoMergePolicy.INSTANCE);
            iwc.setUseCompoundFile(random.nextBoolean());
            iwc.setMaxThreadStates(1);
            final ShardId shardId = new ShardId(new Index("index"), 1);
            DirectoryService directoryService = new LuceneManagedDirectoryService(random);
            Store store = new Store(shardId, ImmutableSettings.EMPTY, directoryService, randomDistributor(random, directoryService), new DummyShardLock(shardId));
            IndexWriter writer = new IndexWriter(store.directory(), iwc);
            final boolean lotsOfSegments = rarely(random);
            for (Document d : docs) {
                writer.addDocument(d);
                if (lotsOfSegments && random.nextBoolean()) {
                    writer.commit();
                } else if (rarely(random)) {
                    writer.commit();
                }
            }
            writer.commit();
            writer.close();
            first = store.getMetadata();
            assertDeleteContent(store, directoryService);
            store.close();
        }
        long time = new Date().getTime();
        while(time == new Date().getTime()) {
            Thread.sleep(10); // bump the time
        }
        Store.MetadataSnapshot second;
        Store store;
        {
            Random random = new Random(seed);
            IndexWriterConfig iwc = new IndexWriterConfig(new MockAnalyzer(random)).setCodec(actualDefaultCodec());
            iwc.setMergePolicy(NoMergePolicy.INSTANCE);
            iwc.setUseCompoundFile(random.nextBoolean());
            iwc.setMaxThreadStates(1);
            final ShardId shardId = new ShardId(new Index("index"), 1);
            DirectoryService directoryService = new LuceneManagedDirectoryService(random);
            store = new Store(shardId, ImmutableSettings.EMPTY, directoryService, randomDistributor(random, directoryService), new DummyShardLock(shardId));
            IndexWriter writer = new IndexWriter(store.directory(), iwc);
            final boolean lotsOfSegments = rarely(random);
            for (Document d : docs) {
                writer.addDocument(d);
                if (lotsOfSegments && random.nextBoolean()) {
                    writer.commit();
                } else if (rarely(random)) {
                    writer.commit();
                }
            }
            writer.commit();
            writer.close();
            second = store.getMetadata();
        }
        Store.RecoveryDiff diff = first.recoveryDiff(second);
        assertThat(first.size(), equalTo(second.size()));
        for (StoreFileMetaData md : first) {
            assertThat(second.get(md.name()), notNullValue());
            // si files are different - containing timestamps etc
            assertThat(second.get(md.name()).isSame(md), equalTo(false));
        }
        assertThat(diff.different.size(), equalTo(first.size()));
        assertThat(diff.identical.size(), equalTo(0)); // in lucene 5 nothing is identical - we use random ids in file headers
        assertThat(diff.missing, empty());

        // check the self diff
        Store.RecoveryDiff selfDiff = first.recoveryDiff(first);
        assertThat(selfDiff.identical.size(), equalTo(first.size()));
        assertThat(selfDiff.different, empty());
        assertThat(selfDiff.missing, empty());


        // lets add some deletes
        Random random = new Random(seed);
        IndexWriterConfig iwc = new IndexWriterConfig(new MockAnalyzer(random)).setCodec(actualDefaultCodec());
        iwc.setMergePolicy(NoMergePolicy.INSTANCE);
        iwc.setUseCompoundFile(random.nextBoolean());
        iwc.setMaxThreadStates(1);
        iwc.setOpenMode(IndexWriterConfig.OpenMode.APPEND);
        IndexWriter writer = new IndexWriter(store.directory(), iwc);
        writer.deleteDocuments(new Term("id", Integer.toString(random().nextInt(numDocs))));
        writer.commit();
        writer.close();
        Store.MetadataSnapshot metadata = store.getMetadata();
        StoreFileMetaData delFile = null;
        for (StoreFileMetaData md : metadata) {
            if (md.name().endsWith(".liv")) {
                delFile = md;
                break;
            }
        }
        Store.RecoveryDiff afterDeleteDiff = metadata.recoveryDiff(second);
        if (delFile != null) {
            assertThat(afterDeleteDiff.identical.size(), equalTo(metadata.size()-2)); // segments_N + del file
            assertThat(afterDeleteDiff.different.size(), equalTo(0));
            assertThat(afterDeleteDiff.missing.size(), equalTo(2));
        } else {
            // an entire segment must be missing (single doc segment got dropped)
            assertThat(afterDeleteDiff.identical.size(), greaterThan(0));
            assertThat(afterDeleteDiff.different.size(), equalTo(0));
            assertThat(afterDeleteDiff.missing.size(), equalTo(1)); // the commit file is different
        }

        // check the self diff
        selfDiff = metadata.recoveryDiff(metadata);
        assertThat(selfDiff.identical.size(), equalTo(metadata.size()));
        assertThat(selfDiff.different, empty());
        assertThat(selfDiff.missing, empty());

        // add a new commit
        iwc = new IndexWriterConfig(new MockAnalyzer(random)).setCodec(actualDefaultCodec());
        iwc.setMergePolicy(NoMergePolicy.INSTANCE);
        iwc.setUseCompoundFile(true); // force CFS - easier to test here since we know it will add 3 files
        iwc.setMaxThreadStates(1);
        iwc.setOpenMode(IndexWriterConfig.OpenMode.APPEND);
        writer = new IndexWriter(store.directory(), iwc);
        writer.addDocument(docs.get(0));
        writer.close();

        Store.MetadataSnapshot newCommitMetaData = store.getMetadata();
        Store.RecoveryDiff newCommitDiff = newCommitMetaData.recoveryDiff(metadata);
        if (delFile != null) {
            assertThat(newCommitDiff.identical.size(), equalTo(newCommitMetaData.size()-5)); // segments_N, del file, cfs, cfe, si for the new segment
            assertThat(newCommitDiff.different.size(), equalTo(1)); // the del file must be different
            assertThat(newCommitDiff.different.get(0).name(), endsWith(".liv"));
            assertThat(newCommitDiff.missing.size(), equalTo(4)); // segments_N,cfs, cfe, si for the new segment
        } else {
            assertThat(newCommitDiff.identical.size(), equalTo(newCommitMetaData.size() - 4)); // segments_N, cfs, cfe, si for the new segment
            assertThat(newCommitDiff.different.size(), equalTo(0));
            assertThat(newCommitDiff.missing.size(), equalTo(4)); // an entire segment must be missing (single doc segment got dropped)  plus the commit is different
        }

        deleteContent(store.directory());
        IOUtils.close(store);
    }

    @Test
    public void testCleanupFromSnapshot() throws IOException {
        final ShardId shardId = new ShardId(new Index("index"), 1);
        DirectoryService directoryService = new LuceneManagedDirectoryService(random());
        Store store = new Store(shardId, ImmutableSettings.EMPTY, directoryService, randomDistributor(directoryService), new DummyShardLock(shardId));
        // this time random codec....
        IndexWriterConfig indexWriterConfig = newIndexWriterConfig(random(), new MockAnalyzer(random())).setCodec(actualDefaultCodec());
        // we keep all commits and that allows us clean based on multiple snapshots
        indexWriterConfig.setIndexDeletionPolicy(NoDeletionPolicy.INSTANCE);
        IndexWriter writer = new IndexWriter(store.directory(), indexWriterConfig);
        int docs = 1 + random().nextInt(100);
        int numCommits = 0;
        for (int i = 0; i < docs; i++) {
            if (i > 0 && randomIntBetween(0, 10 ) == 0) {
                writer.commit();
                numCommits++;
            }
            Document doc = new Document();
            doc.add(new TextField("id", "" + i, random().nextBoolean() ? Field.Store.YES : Field.Store.NO));
            doc.add(new TextField("body", TestUtil.randomRealisticUnicodeString(random()), random().nextBoolean() ? Field.Store.YES : Field.Store.NO));
            doc.add(new SortedDocValuesField("dv", new BytesRef(TestUtil.randomRealisticUnicodeString(random()))));
            writer.addDocument(doc);

        }
        if (numCommits < 1) {
            writer.commit();
            Document doc = new Document();
            doc.add(new TextField("id", "" + docs++, random().nextBoolean() ? Field.Store.YES : Field.Store.NO));
            doc.add(new TextField("body", TestUtil.randomRealisticUnicodeString(random()), random().nextBoolean() ? Field.Store.YES : Field.Store.NO));
            doc.add(new SortedDocValuesField("dv", new BytesRef(TestUtil.randomRealisticUnicodeString(random()))));
            writer.addDocument(doc);
        }

        Store.MetadataSnapshot firstMeta = store.getMetadata();

        if (random().nextBoolean()) {
            for (int i = 0; i < docs; i++) {
                if (random().nextBoolean()) {
                    Document doc = new Document();
                    doc.add(new TextField("id", "" + i, random().nextBoolean() ? Field.Store.YES : Field.Store.NO));
                    doc.add(new TextField("body", TestUtil.randomRealisticUnicodeString(random()), random().nextBoolean() ? Field.Store.YES : Field.Store.NO));
                    writer.updateDocument(new Term("id", "" + i), doc);
                }
            }
        }
        writer.commit();
        writer.close();

        Store.MetadataSnapshot secondMeta = store.getMetadata();

        Store.LegacyChecksums checksums = new Store.LegacyChecksums();
        Map<String, StoreFileMetaData> legacyMeta = new HashMap<>();
        for (String file : store.directory().listAll()) {
            if (file.equals("write.lock") || file.equals(IndexFileNames.OLD_SEGMENTS_GEN)) {
                continue;
            }
            BytesRef hash = new BytesRef();
            if (file.startsWith("segments")) {
                hash = Store.MetadataSnapshot.hashFile(store.directory(), file);
            }
            StoreFileMetaData storeFileMetaData = new StoreFileMetaData(file, store.directory().fileLength(file), file + "checksum", null, hash);
            legacyMeta.put(file, storeFileMetaData);
            checksums.add(storeFileMetaData);
        }
        checksums.write(store); // write one checksum file here - we expect it to survive all the cleanups

        if (randomBoolean()) {
            store.cleanupAndVerify("test", firstMeta);
            String[] strings = store.directory().listAll();
            int numChecksums = 0;
            int numNotFound = 0;
            for (String file : strings) {
                assertTrue(firstMeta.contains(file) || Store.isChecksum(file) || file.equals("write.lock"));
                if (Store.isChecksum(file)) {
                    numChecksums++;
                } else  if (secondMeta.contains(file) == false) {
                    numNotFound++;
                }

            }
            assertTrue("at least one file must not be in here since we have two commits?", numNotFound > 0);
            assertEquals("we wrote one checksum but it's gone now? - checksums are supposed to be kept", numChecksums, 1);
        } else {
            store.cleanupAndVerify("test", secondMeta);
            String[] strings = store.directory().listAll();
            int numChecksums = 0;
            int numNotFound = 0;
            for (String file : strings) {
                assertTrue(file, secondMeta.contains(file) || Store.isChecksum(file) || file.equals("write.lock"));
                if (Store.isChecksum(file)) {
                    numChecksums++;
                } else  if (firstMeta.contains(file) == false) {
                    numNotFound++;
                }

            }
            assertTrue("at least one file must not be in here since we have two commits?", numNotFound > 0);
            assertEquals("we wrote one checksum but it's gone now? - checksums are supposed to be kept", numChecksums, 1);
        }

        deleteContent(store.directory());
        IOUtils.close(store);
    }

    @Test
    public void testCleanUpWithLegacyChecksums() throws IOException {
        Map<String, StoreFileMetaData> metaDataMap = new HashMap<>();
        metaDataMap.put("segments_1", new StoreFileMetaData("segments_1", 50, null, null, new BytesRef(new byte[]{1})));
        metaDataMap.put("_0_1.del", new StoreFileMetaData("_0_1.del", 42, "foobarbaz", null, new BytesRef()));
        Store.MetadataSnapshot snapshot = new Store.MetadataSnapshot(metaDataMap);

        final ShardId shardId = new ShardId(new Index("index"), 1);
        DirectoryService directoryService = new LuceneManagedDirectoryService(random());
        Store store = new Store(shardId, ImmutableSettings.EMPTY, directoryService, randomDistributor(directoryService), new DummyShardLock(shardId));
        for (String file : metaDataMap.keySet()) {
            try (IndexOutput output = store.directory().createOutput(file, IOContext.DEFAULT)) {
                BytesRef bytesRef = new BytesRef(TestUtil.randomRealisticUnicodeString(random(), 10, 1024));
                output.writeBytes(bytesRef.bytes, bytesRef.offset, bytesRef.length);
                CodecUtil.writeFooter(output);
            }
        }

        store.verifyAfterCleanup(snapshot, snapshot);
        deleteContent(store.directory());
        IOUtils.close(store);
    }

    public void testOnCloseCallback() throws IOException {
        final ShardId shardId = new ShardId(new Index(randomRealisticUnicodeOfCodepointLengthBetween(1, 10)), randomIntBetween(0, 100));
        DirectoryService directoryService = new LuceneManagedDirectoryService(random());
        final AtomicInteger count = new AtomicInteger(0);
        final ShardLock lock = new DummyShardLock(shardId);

        Store store = new Store(shardId, ImmutableSettings.EMPTY, directoryService, randomDistributor(directoryService), lock , new Store.OnClose() {
            @Override
            public void handle(ShardLock theLock) {
                assertEquals(shardId, theLock.getShardId());
                assertEquals(lock, theLock);
                count.incrementAndGet();
            }
        });
        assertEquals(count.get(), 0);

        final int iters = randomIntBetween(1, 10);
        for (int i = 0; i < iters; i++) {
            store.close();
        }

        assertEquals(count.get(), 1);
    }

    @Test
    public void testStoreStats() throws IOException {
        final ShardId shardId = new ShardId(new Index("index"), 1);
        DirectoryService directoryService = new LuceneManagedDirectoryService(random());
        Settings settings = ImmutableSettings.builder().put(Store.INDEX_STORE_STATS_REFRESH_INTERVAL, TimeValue.timeValueMinutes(0)).build();
        Store store = new Store(shardId, settings, directoryService, randomDistributor(directoryService), new DummyShardLock(shardId));

        StoreStats stats = store.stats();
        assertEquals(stats.getSize().bytes(), 0);

        Directory dir = store.directory();
        final long length;
        try (IndexOutput output = dir.createOutput("foo.bar", IOContext.DEFAULT)) {
            int iters = scaledRandomIntBetween(10, 100);
            for (int i = 0; i < iters; i++) {
                BytesRef bytesRef = new BytesRef(TestUtil.randomRealisticUnicodeString(random(), 10, 1024));
                output.writeBytes(bytesRef.bytes, bytesRef.offset, bytesRef.length);
            }
            length = output.getFilePointer();
        }

        assertTrue(store.directory().listAll().length > 0);
        stats = store.stats();
        assertEquals(stats.getSizeInBytes(), length);

        deleteContent(store.directory());
        IOUtils.close(store);
    }


    public static void deleteContent(Directory directory) throws IOException {
        final String[] files = directory.listAll();
        final List<IOException> exceptions = new ArrayList<>();
        for (String file : files) {
            try {
                directory.deleteFile(file);
            } catch (NoSuchFileException | FileNotFoundException e) {
                // ignore
            } catch (IOException e) {
                exceptions.add(e);
            }
        }
        ExceptionsHelper.rethrowAndSuppress(exceptions);
    }
}

/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.jena.query.text ;

import java.io.IOException ;
import java.util.ArrayList ;
import java.util.HashMap ;
import java.util.List ;
import java.util.Map ;
import java.util.Map.Entry ;

import org.apache.lucene.analysis.Analyzer ;
import org.apache.lucene.analysis.core.KeywordAnalyzer ;
import org.apache.lucene.analysis.miscellaneous.PerFieldAnalyzerWrapper ;
import org.apache.lucene.analysis.standard.StandardAnalyzer ;
import org.apache.lucene.document.Document ;
import org.apache.lucene.document.Field ;
import org.apache.lucene.document.FieldType ;
import org.apache.lucene.document.StringField ;
import org.apache.lucene.document.TextField ;
import org.apache.lucene.index.DirectoryReader ;
import org.apache.lucene.index.IndexReader ;
import org.apache.lucene.index.IndexWriter ;
import org.apache.lucene.index.IndexWriterConfig ;
import org.apache.lucene.index.Term;
import org.apache.lucene.queryparser.classic.ParseException ;
import org.apache.lucene.queryparser.classic.QueryParser ;
import org.apache.lucene.queryparser.classic.QueryParserBase ;
import org.apache.lucene.search.IndexSearcher ;
import org.apache.lucene.search.Query ;
import org.apache.lucene.search.ScoreDoc ;
import org.apache.lucene.store.Directory ;
import org.apache.lucene.util.Version ;
import org.slf4j.Logger ;
import org.slf4j.LoggerFactory ;

import com.hp.hpl.jena.graph.Node ;
import com.hp.hpl.jena.graph.NodeFactory ;
import com.hp.hpl.jena.sparql.util.NodeFactoryExtra ;

public class TextIndexLucene implements TextIndex {
    private static Logger          log      = LoggerFactory.getLogger(TextIndexLucene.class) ;

    private static int             MAX_N    = 10000 ;
    public static final Version    VER      = Version.LUCENE_46 ;

    public static final FieldType  ftIRI ;
    static {
        ftIRI = new FieldType() ;
        ftIRI.setTokenized(false) ;
        ftIRI.setStored(true) ;
        ftIRI.setIndexed(true) ;
        ftIRI.freeze() ;
    }
    public static final FieldType  ftString = StringField.TYPE_NOT_STORED ;
    public static final FieldType  ftText   = TextField.TYPE_NOT_STORED ;
    // Bigger index, easier to debug!
    // public static final FieldType ftText = TextField.TYPE_STORED ;

    private final EntityDefinition docDef ;
    private final Directory        directory ;
    private final Analyzer         analyzer ;
    private final Analyzer         queryAnalyzer ;

    // The IndexWriter can't be final because we may have to recreate it if rollback() is called.
    // However, it needs to be volatile in case the next write transaction is on a different thread,
    // but we do not need locking because we are assuming that there can only be one writer
    // at a time (enforced elsewhere).
    private volatile IndexWriter   indexWriter ;

    /**
     * Constructs a new TextIndexLucene.
     * 
     * @param directory The Lucene Directory for the index
     * @param def The EntityDefinition that defines how entities are stored in the index
     * @param queryAnalyzer The analyzer to be used to find terms in the query text.  If null, then the analyzer defined by the EntityDefinition will be used.
     */
    public TextIndexLucene(Directory directory, EntityDefinition def, Analyzer queryAnalyzer) {
        this.directory = directory ;
        this.docDef = def ;

        // create the analyzer as a wrapper that uses KeywordAnalyzer for
        // entity and graph fields and StandardAnalyzer for all other
        Map<String, Analyzer> analyzerPerField = new HashMap<>() ;
        analyzerPerField.put(def.getEntityField(), new KeywordAnalyzer()) ;
        if ( def.getGraphField() != null )
            analyzerPerField.put(def.getGraphField(), new KeywordAnalyzer()) ;

        for (String field : def.fields()) {
            Analyzer analyzer = def.getAnalyzer(field);
            if (analyzer != null) {
                analyzerPerField.put(field, analyzer);
            }
        }

        this.analyzer = new PerFieldAnalyzerWrapper(new StandardAnalyzer(VER), analyzerPerField) ;
        this.queryAnalyzer = (null != queryAnalyzer) ? queryAnalyzer : analyzer ;

        openIndexWriter();
    }

    private void openIndexWriter() {
        IndexWriterConfig wConfig = new IndexWriterConfig(VER, analyzer) ;
        try
        {
            indexWriter = new IndexWriter(directory, wConfig) ;
            // Force a commit to create the index, otherwise querying before writing will cause an exception
            indexWriter.commit();
        }
        catch (IOException e)
        {
            throw new TextIndexException(e) ;
        }
    }

    public Directory getDirectory() {
        return directory ;
    }

    public Analyzer getAnalyzer() {
        return analyzer ;
    }

    public Analyzer getQueryAnalyzer() {
        return queryAnalyzer ;
    }

    public IndexWriter getIndexWriter() {
        return indexWriter;
    }

    @Override
    public void prepareCommit() {
        try {
            indexWriter.prepareCommit();
        }
        catch (IOException e) {
            throw new TextIndexException(e);
        }
    }

    @Override
    public void commit() {
        try {
            indexWriter.commit();
        }
        catch (IOException e) {
            throw new TextIndexException(e);
        }
    }

    @Override
    public void rollback() {
        IndexWriter idx = indexWriter;
        indexWriter = null;
        try {
            idx.rollback();
        }
        catch (IOException e) {
            throw new TextIndexException(e);
        }

        // The rollback will close the indexWriter, so we need to reopen it
        openIndexWriter();
    }

    @Override
    public void close() {
        try {
            indexWriter.close() ;
        }
        catch (IOException ex) {
            throw new TextIndexException(ex) ;
        }
    }

    @Override public void updateEntity(Entity entity) {
        if ( log.isDebugEnabled() )
            log.debug("Update entity: " + entity) ;
        try {
            Document doc = doc(entity);
            Term term = new Term(docDef.getEntityField(), entity.getId());
            indexWriter.updateDocument(term, doc);
        } catch (IOException e) {
            throw new TextIndexException(e) ;
        }
    }

    @Override
    public void addEntity(Entity entity) {
        if ( log.isDebugEnabled() )
            log.debug("Add entity: " + entity) ;
        try {
            Document doc = doc(entity) ;
            indexWriter.addDocument(doc) ;
        }
        catch (IOException e) {
            throw new TextIndexException(e) ;
        }
    }

    private Document doc(Entity entity) {
        Document doc = new Document() ;
        Field entField = new Field(docDef.getEntityField(), entity.getId(), ftIRI) ;
        doc.add(entField) ;

        String graphField = docDef.getGraphField() ;
        if ( graphField != null ) {
            Field gField = new Field(graphField, entity.getGraph(), ftString) ;
            doc.add(gField) ;
        }

        for ( Entry<String, Object> e : entity.getMap().entrySet() ) {
            Field field = new Field(e.getKey(), (String)e.getValue(), ftText) ;
            doc.add(field) ;
        }
        return doc ;
    }

    @Override
    public Map<String, Node> get(String uri) {
        try {
            IndexReader indexReader = DirectoryReader.open(directory);
            List<Map<String, Node>> x = get$(indexReader, uri) ;
            if ( x.size() == 0 )
                return null ;
            // if ( x.size() > 1)
            // throw new TextIndexException("Multiple entires for "+uri) ;
            return x.get(0) ;
        }
        catch (Exception ex) {
            throw new TextIndexException(ex) ;
        }
    }

    private static Query parseQuery(String queryString, String primaryField, Analyzer analyzer) throws ParseException {
        QueryParser queryParser = new QueryParser(VER, primaryField, analyzer) ;
        queryParser.setAllowLeadingWildcard(true) ;
        Query query = queryParser.parse(queryString) ;
        return query ;
    }

    private List<Map<String, Node>> get$(IndexReader indexReader, String uri) throws ParseException, IOException {
        String escaped = QueryParserBase.escape(uri) ;
        String qs = docDef.getEntityField() + ":" + escaped ;
        Query query = parseQuery(qs, docDef.getPrimaryField(), queryAnalyzer) ;
        IndexSearcher indexSearcher = new IndexSearcher(indexReader) ;
        ScoreDoc[] sDocs = indexSearcher.search(query, 1).scoreDocs ;
        List<Map<String, Node>> records = new ArrayList<Map<String, Node>>() ;

        // Align and DRY with Solr.
        for ( ScoreDoc sd : sDocs ) {
            //** score :: sd.score
            Document doc = indexSearcher.doc(sd.doc) ;
            String[] x = doc.getValues(docDef.getEntityField()) ;
            if ( x.length != 1 ) {}
            String uriStr = x[0] ;
            Map<String, Node> record = new HashMap<>() ;
            Node entity = NodeFactory.createURI(uriStr) ;
            record.put(docDef.getEntityField(), entity) ;

            for ( String f : docDef.fields() ) {
                // log.info("Field: "+f) ;
                String[] values = doc.getValues(f) ;
                for ( String v : values ) {
                    Node n = entryToNode(v) ;
                    record.put(f, n) ;
                }
                records.add(record) ;
            }
        }
        return records ;
    }

    @Override
    public List<Node> query(String qs) {
        return query(qs, MAX_N) ;
    }

    @Override
    public List<Node> query(String qs, int limit) {
        //** score
        try (IndexReader indexReader = DirectoryReader.open(directory)) {
            return query$(indexReader, qs, limit) ;
        } 
        catch (Exception ex) {
            throw new TextIndexException(ex) ;
        }
    }

    private List<Node> query$(IndexReader indexReader, String qs, int limit) throws ParseException, IOException {
        IndexSearcher indexSearcher = new IndexSearcher(indexReader) ;
        Query query = parseQuery(qs, docDef.getPrimaryField(), queryAnalyzer) ;
        if ( limit <= 0 )
            limit = MAX_N ;
        ScoreDoc[] sDocs = indexSearcher.search(query, limit).scoreDocs ;

        List<Node> results = new ArrayList<>() ;

        // Align and DRY with Solr.
        for ( ScoreDoc sd : sDocs ) {
            Document doc = indexSearcher.doc(sd.doc) ;
            String[] values = doc.getValues(docDef.getEntityField()) ;
            for ( String v : values ) {
                Node n = TextQueryFuncs.stringToNode(v) ;
                results.add(n) ;
            }
        }
        return results ;
    }

    @Override
    public EntityDefinition getDocDef() {
        return docDef ;
    }

    private Node entryToNode(String v) {
        // TEMP
        return NodeFactoryExtra.createLiteralNode(v, null, null) ;
    }
}

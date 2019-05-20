/*
 * Copyright (c) 2002-2019 "Neo4j,"
 * Neo4j Sweden AB [http://neo4j.com]
 *
 * This file is part of Neo4j.
 *
 * Neo4j is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.neo4j.consistency;

import org.apache.commons.lang3.StringUtils;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.RuleChain;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.neo4j.configuration.Config;
import org.neo4j.configuration.GraphDatabaseSettings;
import org.neo4j.configuration.Settings;
import org.neo4j.consistency.ConsistencyCheckService.Result;
import org.neo4j.consistency.checking.GraphStoreFixture;
import org.neo4j.consistency.checking.full.ConsistencyCheckIncompleteException;
import org.neo4j.dbms.database.DatabaseManagementService;
import org.neo4j.exceptions.KernelException;
import org.neo4j.graphdb.GraphDatabaseService;
import org.neo4j.graphdb.Label;
import org.neo4j.graphdb.Node;
import org.neo4j.graphdb.RelationshipType;
import org.neo4j.graphdb.Transaction;
import org.neo4j.graphdb.factory.DatabaseManagementServiceBuilder;
import org.neo4j.graphdb.schema.IndexDefinition;
import org.neo4j.internal.helpers.Strings;
import org.neo4j.internal.helpers.progress.ProgressMonitorFactory;
import org.neo4j.internal.recordstorage.RecordStorageEngine;
import org.neo4j.io.fs.DefaultFileSystemAbstraction;
import org.neo4j.io.fs.FileUtils;
import org.neo4j.io.layout.DatabaseLayout;
import org.neo4j.kernel.impl.store.NeoStores;
import org.neo4j.kernel.impl.store.RelationshipStore;
import org.neo4j.kernel.impl.store.record.NodeRecord;
import org.neo4j.kernel.impl.store.record.RecordLoad;
import org.neo4j.kernel.impl.store.record.RelationshipRecord;
import org.neo4j.kernel.impl.transaction.log.files.LogFilesBuilder;
import org.neo4j.kernel.internal.GraphDatabaseAPI;
import org.neo4j.logging.NullLogProvider;
import org.neo4j.test.TestDatabaseManagementServiceBuilder;
import org.neo4j.test.rule.TestDirectory;

import static java.lang.String.format;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.neo4j.configuration.GraphDatabaseSettings.DEFAULT_DATABASE_NAME;
import static org.neo4j.configuration.GraphDatabaseSettings.SchemaIndex.NATIVE30;
import static org.neo4j.configuration.GraphDatabaseSettings.SchemaIndex.NATIVE_BTREE10;
import static org.neo4j.internal.helpers.collection.MapUtil.stringMap;
import static org.neo4j.test.Property.property;
import static org.neo4j.test.Property.set;

public class ConsistencyCheckServiceIntegrationTest
{
    private final GraphStoreFixture fixture = new GraphStoreFixture( getRecordFormatName() )
    {
        @Override
        protected void generateInitialData( GraphDatabaseService graphDb )
        {
            try ( org.neo4j.graphdb.Transaction tx = graphDb.beginTx() )
            {
                Node node1 = set( graphDb.createNode() );
                Node node2 = set( graphDb.createNode(), property( "key", "exampleValue" ) );
                node1.createRelationshipTo( node2, RelationshipType.withName( "C" ) );
                tx.success();
            }
        }
    };

    private final DefaultFileSystemAbstraction fs = new DefaultFileSystemAbstraction();
    private final TestDirectory testDirectory = TestDirectory.testDirectory( fs );
    @Rule
    public final RuleChain chain = RuleChain.outerRule( testDirectory ).around( fixture );
    private DatabaseManagementService managementService;

    @Test
    public void reportNotUsedRelationshipReferencedInChain() throws Exception
    {
        prepareDbWithDeletedRelationshipPartOfTheChain();

        Date timestamp = new Date();
        ConsistencyCheckService service = new ConsistencyCheckService( timestamp );
        Config configuration = Config.defaults( settings() );

        ConsistencyCheckService.Result result = runFullConsistencyCheck( service, configuration );

        assertFalse( result.isSuccessful() );

        File reportFile = result.reportFile();
        assertTrue( "Consistency check report file should be generated.", reportFile.exists() );
        assertThat( "Expected to see report about not deleted relationship record present as part of a chain",
                Files.readString( reportFile.toPath() ),
                containsString( "The relationship record is not in use, but referenced from relationships chain.") );
    }

    @Test
    public void shouldFailOnDatabaseInNeedOfRecovery() throws IOException
    {
        nonRecoveredDatabase();
        ConsistencyCheckService service = new ConsistencyCheckService();
        try
        {
            Map<String,String> settings = settings();
            Config defaults = Config.defaults( settings );
            runFullConsistencyCheck( service, defaults );
            fail();
        }
        catch ( ConsistencyCheckIncompleteException e )
        {
            assertEquals( e.getCause().getMessage(),
                    Strings.joinAsLines( "Active logical log detected, this might be a source of inconsistencies.", "Please recover database.",
                            "To perform recovery please start database in single mode and perform clean shutdown." ) );
        }
    }

    @Test
    public void ableToDeleteDatabaseDirectoryAfterConsistencyCheckRun() throws ConsistencyCheckIncompleteException, IOException
    {
        prepareDbWithDeletedRelationshipPartOfTheChain();
        ConsistencyCheckService service = new ConsistencyCheckService();
        Result consistencyCheck = runFullConsistencyCheck( service, Config.defaults( settings() ) );
        assertFalse( consistencyCheck.isSuccessful() );
        // using commons file utils since they do not forgive not closed file descriptors on windows
        org.apache.commons.io.FileUtils.deleteDirectory( fixture.databaseLayout().databaseDirectory() );
    }

    @Test
    public void shouldSucceedIfStoreIsConsistent() throws Exception
    {
        // given
        Date timestamp = new Date();
        ConsistencyCheckService service = new ConsistencyCheckService( timestamp );
        Config configuration = Config.defaults( settings() );

        // when
        ConsistencyCheckService.Result result = runFullConsistencyCheck( service, configuration );

        // then
        assertTrue( result.isSuccessful() );
        File reportFile = result.reportFile();
        assertFalse( "Unexpected generation of consistency check report file: " + reportFile, reportFile.exists() );
    }

    @Test
    public void shouldFailIfTheStoreInNotConsistent() throws Exception
    {
        // given
        breakNodeStore();
        Date timestamp = new Date();
        ConsistencyCheckService service = new ConsistencyCheckService( timestamp );
        String logsDir = testDirectory.directory().getPath();
        Config configuration = Config.defaults(
                settings( GraphDatabaseSettings.logs_directory.name(), logsDir ) );

        // when
        ConsistencyCheckService.Result result = runFullConsistencyCheck( service, configuration );

        // then
        assertFalse( result.isSuccessful() );
        String reportFile = format( "inconsistencies-%s.report",
                new SimpleDateFormat( "yyyy-MM-dd.HH.mm.ss" ).format( timestamp ) );
        assertEquals( new File( logsDir, reportFile ), result.reportFile() );
        assertTrue( "Inconsistency report file not generated", result.reportFile().exists() );
    }

    @Test
    public void shouldNotReportDuplicateForHugeLongValues() throws Exception
    {
        // given
        ConsistencyCheckService service = new ConsistencyCheckService();
        Config configuration = Config.defaults( settings() );
        DatabaseManagementService managementService =
                new TestDatabaseManagementServiceBuilder( testDirectory.storeDir() ).setConfig( GraphDatabaseSettings.record_format, getRecordFormatName() )
                .setConfig( "dbms.backup.enabled", "false" ).build();
        GraphDatabaseService db = managementService.database( DEFAULT_DATABASE_NAME );

        String propertyKey = "itemId";
        Label label = Label.label( "Item" );
        try ( Transaction tx = db.beginTx() )
        {
            db.schema().constraintFor( label ).assertPropertyIsUnique( propertyKey ).create();
            tx.success();
        }
        try ( Transaction tx = db.beginTx() )
        {
            set( db.createNode( label ), property( propertyKey, 973305894188596880L ) );
            set( db.createNode( label ), property( propertyKey, 973305894188596864L ) );
            tx.success();
        }
        managementService.shutdown();

        // when
        Result result = runFullConsistencyCheck( service, configuration );

        // then
        assertTrue( result.isSuccessful() );
    }

    @Test
    public void shouldAllowGraphCheckDisabled() throws ConsistencyCheckIncompleteException
    {
        GraphDatabaseService gds = getGraphDatabaseService();

        try ( Transaction tx = gds.beginTx() )
        {
            gds.createNode();
            tx.success();
        }

        managementService.shutdown();

        ConsistencyCheckService service = new ConsistencyCheckService();
        Config configuration = Config.defaults(
                settings( ConsistencyCheckSettings.consistency_check_graph.name(), Settings.FALSE ) );

        // when
        Result result = runFullConsistencyCheck( service, configuration );

        // then
        assertTrue( result.isSuccessful() );
    }

    @Test
    public void shouldReportMissingSchemaIndex() throws Exception
    {
        // given
        DatabaseLayout databaseLayout = testDirectory.databaseLayout();
        GraphDatabaseService gds = getGraphDatabaseService( testDirectory.storeDir() );

        Label label = Label.label( "label" );
        String propKey = "propKey";
        createIndex( gds, label, propKey );

        managementService.shutdown();

        // when
        File schemaDir = findFile( databaseLayout, "schema" );
        FileUtils.deleteRecursively( schemaDir );

        ConsistencyCheckService service = new ConsistencyCheckService();
        Config configuration = Config.defaults( settings() );
        Result result = runFullConsistencyCheck( service, configuration, databaseLayout );

        // then
        assertTrue( result.isSuccessful() );
        File reportFile = result.reportFile();
        assertTrue( "Consistency check report file should be generated.", reportFile.exists() );
        assertThat( "Expected to see report about schema index not being online",
                Files.readString( reportFile.toPath() ), allOf(
                        containsString( "schema rule" ),
                        containsString( "not online" )
                ) );
    }

    @Test
    public void oldLuceneSchemaIndexShouldBeConsideredConsistentWithFusionProvider() throws Exception
    {
        DatabaseLayout databaseLayout = testDirectory.databaseLayout();
        String defaultSchemaProvider = GraphDatabaseSettings.default_schema_provider.name();
        Label label = Label.label( "label" );
        String propKey = "propKey";

        // Given a lucene index
        GraphDatabaseService db = getGraphDatabaseService( databaseLayout.databaseDirectory(), defaultSchemaProvider, NATIVE30.providerName() );
        createIndex( db, label, propKey );
        try ( Transaction tx = db.beginTx() )
        {
            db.createNode( label ).setProperty( propKey, 1 );
            db.createNode( label ).setProperty( propKey, "string" );
            tx.success();
        }
        managementService.shutdown();

        ConsistencyCheckService service = new ConsistencyCheckService();
        Config configuration =
                Config.defaults( settings( defaultSchemaProvider, NATIVE_BTREE10.providerName() ) );
        Result result = runFullConsistencyCheck( service, configuration, databaseLayout );
        assertTrue( result.isSuccessful() );
    }

    private static void createIndex( GraphDatabaseService gds, Label label, String propKey )
    {
        IndexDefinition indexDefinition;

        try ( Transaction tx = gds.beginTx() )
        {
            indexDefinition = gds.schema().indexFor( label ).on( propKey ).create();
            tx.success();
        }

        try ( Transaction tx = gds.beginTx() )
        {
            gds.schema().awaitIndexOnline( indexDefinition, 1, TimeUnit.MINUTES );
            tx.success();
        }
    }

    private static File findFile( DatabaseLayout databaseLayout, String targetFile )
    {
        File file = databaseLayout.file( targetFile );
        if ( !file.exists() )
        {
            fail( "Could not find file " + targetFile );
        }
        return file;
    }

    private GraphDatabaseService getGraphDatabaseService()
    {
        return getGraphDatabaseService( testDirectory.absolutePath() );
    }

    private GraphDatabaseService getGraphDatabaseService( File storeDir )
    {
        return getGraphDatabaseService( storeDir, new String[0] );
    }

    private GraphDatabaseService getGraphDatabaseService( File storeDir, String... settings )
    {
        DatabaseManagementServiceBuilder builder = new TestDatabaseManagementServiceBuilder( storeDir );
        builder.setConfigRaw( settings( settings ) );

        managementService = builder.build();
        return managementService.database( DEFAULT_DATABASE_NAME );
    }

    private void prepareDbWithDeletedRelationshipPartOfTheChain()
    {
        DatabaseManagementService managementService =
                new TestDatabaseManagementServiceBuilder( testDirectory.storeDir() ).setConfig( GraphDatabaseSettings.record_format, getRecordFormatName() )
                .setConfig( "dbms.backup.enabled", "false" ).build();
        GraphDatabaseAPI db = (GraphDatabaseAPI) managementService.database( DEFAULT_DATABASE_NAME );
        try
        {

            RelationshipType relationshipType = RelationshipType.withName( "testRelationshipType" );
            try ( Transaction tx = db.beginTx() )
            {
                Node node1 = set( db.createNode() );
                Node node2 = set( db.createNode(), property( "key", "value" ) );
                node1.createRelationshipTo( node2, relationshipType );
                node1.createRelationshipTo( node2, relationshipType );
                node1.createRelationshipTo( node2, relationshipType );
                node1.createRelationshipTo( node2, relationshipType );
                node1.createRelationshipTo( node2, relationshipType );
                node1.createRelationshipTo( node2, relationshipType );
                tx.success();
            }

            RecordStorageEngine recordStorageEngine = db.getDependencyResolver().resolveDependency( RecordStorageEngine.class );

            NeoStores neoStores = recordStorageEngine.testAccessNeoStores();
            RelationshipStore relationshipStore = neoStores.getRelationshipStore();
            RelationshipRecord relationshipRecord = new RelationshipRecord( -1 );
            RelationshipRecord record = relationshipStore.getRecord( 4, relationshipRecord, RecordLoad.FORCE );
            record.setInUse( false );
            relationshipStore.updateRecord( relationshipRecord );
        }
        finally
        {
            managementService.shutdown();
        }
    }

    private void nonRecoveredDatabase() throws IOException
    {
        File tmpLogDir = new File( testDirectory.directory(), "logs" );
        fs.mkdir( tmpLogDir );
        var databaseLayout = testDirectory.databaseLayout();
        DatabaseManagementService managementService =
                new TestDatabaseManagementServiceBuilder( testDirectory.storeDir() ).setConfig( GraphDatabaseSettings.record_format, getRecordFormatName() )
                .setConfig( "dbms.backup.enabled", "false" ).build();
        GraphDatabaseAPI db = (GraphDatabaseAPI) managementService.database( DEFAULT_DATABASE_NAME );

        RelationshipType relationshipType = RelationshipType.withName( "testRelationshipType" );
        try ( Transaction tx = db.beginTx() )
        {
            Node node1 = set( db.createNode() );
            Node node2 = set( db.createNode(), property( "key", "value" ) );
            node1.createRelationshipTo( node2, relationshipType );
            tx.success();
        }
        File[] txLogs = LogFilesBuilder.logFilesBasedOnlyBuilder( databaseLayout.getTransactionLogsDirectory(), fs ).build().logFiles();
        for ( File file : txLogs )
        {
            fs.copyToDirectory( file, tmpLogDir );
        }
        managementService.shutdown();
        for ( File txLog : txLogs )
        {
            fs.deleteFile( txLog );
        }

        for ( File file : LogFilesBuilder.logFilesBasedOnlyBuilder( tmpLogDir, fs ).build().logFiles() )
        {
            fs.moveToDirectory( file, databaseLayout.getTransactionLogsDirectory() );
        }
    }

    protected Map<String,String> settings( String... strings )
    {
        Map<String, String> defaults = new HashMap<>();
        defaults.put( GraphDatabaseSettings.pagecache_memory.name(), "8m" );
        defaults.put( GraphDatabaseSettings.record_format.name(), getRecordFormatName() );
        defaults.put( "dbms.backup.enabled", "false" );
        return stringMap( defaults, strings );
    }

    private void breakNodeStore() throws KernelException
    {
        fixture.apply( new GraphStoreFixture.Transaction()
        {
            @Override
            protected void transactionData( GraphStoreFixture.TransactionDataBuilder tx,
                    GraphStoreFixture.IdGenerator next )
            {
                tx.create( new NodeRecord( next.node(), false, next.relationship(), -1 ) );
            }
        } );
    }

    private Result runFullConsistencyCheck( ConsistencyCheckService service, Config configuration )
            throws ConsistencyCheckIncompleteException
    {
        return runFullConsistencyCheck( service, configuration, fixture.databaseLayout() );
    }

    private static Result runFullConsistencyCheck( ConsistencyCheckService service, Config configuration, DatabaseLayout databaseLayout )
            throws ConsistencyCheckIncompleteException
    {
        return service.runFullConsistencyCheck( databaseLayout,
                configuration, ProgressMonitorFactory.NONE, NullLogProvider.getInstance(), false );
    }

    protected String getRecordFormatName()
    {
        return StringUtils.EMPTY;
    }
}

/*
 * Copyright (c) 2002-2020 "Neo4j,"
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
package org.neo4j.bolt;

import org.assertj.core.api.Condition;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import org.neo4j.bolt.testing.TransportTestUtil;
import org.neo4j.bolt.testing.client.SocketConnection;
import org.neo4j.bolt.transport.Neo4jWithSocket;
import org.neo4j.bolt.v3.messaging.request.HelloMessage;
import org.neo4j.bolt.v4.messaging.PullMessage;
import org.neo4j.bolt.v4.messaging.RunMessage;
import org.neo4j.configuration.connectors.BoltConnector;
import org.neo4j.configuration.helpers.SocketAddress;
import org.neo4j.internal.helpers.HostnamePort;
import org.neo4j.values.AnyValue;

import static java.util.concurrent.TimeUnit.MINUTES;
import static org.assertj.core.api.Assertions.assertThat;
import static org.neo4j.bolt.testing.MessageConditions.msgRecord;
import static org.neo4j.bolt.testing.MessageConditions.msgSuccess;
import static org.neo4j.bolt.testing.StreamConditions.eqRecord;
import static org.neo4j.bolt.testing.TransportTestUtil.eventuallyReceives;
import static org.neo4j.bolt.v4.BoltProtocolV4ComponentFactory.newMessageEncoder;
import static org.neo4j.internal.helpers.collection.MapUtil.map;
import static org.neo4j.kernel.impl.util.ValueUtils.asMapValue;
import static org.neo4j.values.storable.Values.longValue;

public class MultipleBoltServerPortsStressTest
{
    private static final int DURATION_IN_MINUTES = 1;
    private static final int NUMBER_OF_THREADS = 10;

    private static final String USER_AGENT = "TestClient/4.1";
    private static TransportTestUtil util;

    @Rule
    public Neo4jWithSocket server = new Neo4jWithSocket( getClass(), settings ->
    {
        settings.put( BoltConnector.enabled, true );
        settings.put( BoltConnector.listen_address, new SocketAddress( 0 ) );

        settings.put( BoltConnector.connector_routing_enabled, true );
        settings.put( BoltConnector.connector_routing_listen_address, new SocketAddress( 0 ) );
    } );

    @Before
    public void setUp() throws Exception
    {
        util = new TransportTestUtil( newMessageEncoder() );
    }

    @Test
    public void splitTrafficBetweenPorts() throws Exception
    {
        SocketConnection externalConnection = new SocketConnection();
        SocketConnection internalConnection = new SocketConnection();
        try
        {
            HostnamePort externalAddress = server.lookupConnector( BoltConnector.NAME );
            HostnamePort internalAddress = server.lookupConnector( BoltConnector.INTERNAL_NAME );

            executeStressTest( Executors.newFixedThreadPool( NUMBER_OF_THREADS ), externalAddress, internalAddress );
        }
        finally
        {
            externalConnection.disconnect();
            internalConnection.disconnect();
        }
    }

    private void executeStressTest( ExecutorService executorPool, HostnamePort external, HostnamePort internal ) throws Exception
    {
        long finishTimeMillis = System.currentTimeMillis() + MINUTES.toMillis( MultipleBoltServerPortsStressTest.DURATION_IN_MINUTES );
        AtomicBoolean failureFlag = new AtomicBoolean( false );

        for ( int i = 0; i < MultipleBoltServerPortsStressTest.NUMBER_OF_THREADS; i++ )
        {
            SocketConnection connection = new SocketConnection();

            // split connections evenly between internal and external
            if ( i % 2 == 0 )
            {
                initializeConnection( connection, internal );
            }
            else
            {
                initializeConnection( connection, external );
            }

            executorPool.submit( workload( failureFlag, connection, finishTimeMillis ) );
        }

        executorPool.shutdown();
        executorPool.awaitTermination( DURATION_IN_MINUTES, MINUTES );
        assertThat( failureFlag ).isFalse();
    }

    private void initializeConnection( SocketConnection connection, HostnamePort address ) throws Exception
    {
        connection.connect( address ).send( util.defaultAcceptedVersions() );
        assertThat( connection ).satisfies( eventuallyReceives( new byte[]{0, 0, 1, 4} ) );

        connection.send( util.chunk( new HelloMessage( map( "user_agent", USER_AGENT ) ) ) );
        assertThat( connection ).satisfies( util.eventuallyReceives( msgSuccess() ) );
    }

    private static Condition<AnyValue> longValueCondition( long expected )
    {
        return new Condition<>( value -> value.equals( longValue( expected ) ), "equals" );
    }

    private Runnable workload( AtomicBoolean failureFlag, SocketConnection connection, long finishTimeMillis )
    {
        return () ->
        {
            while ( !failureFlag.get() && System.currentTimeMillis() < finishTimeMillis )
            {
                try
                {
                    connection.send( util.chunk( new RunMessage( "RETURN 1" ), new PullMessage( asMapValue( map( "n", -1L ) ) ) ) );
                }
                catch ( IOException e )
                {
                    e.printStackTrace();
                }

                try
                {
                    assertThat( connection ).satisfies( util.eventuallyReceives( msgSuccess() ) );
                    assertThat( connection ).satisfies( util.eventuallyReceives( msgRecord( eqRecord( longValueCondition( 1L ) ) ) ) );
                    assertThat( connection ).satisfies( util.eventuallyReceives( msgSuccess() ) );
                }
                catch ( AssertionError e )
                {
                    e.printStackTrace();
                    failureFlag.set( true );
                }
            }
        };
    }
}

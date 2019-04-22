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
package org.neo4j.kernel.internal;

import org.apache.commons.lang3.StringUtils;

import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

import org.neo4j.graphdb.Node;
import org.neo4j.graphdb.Relationship;
import org.neo4j.graphdb.event.LabelEntry;
import org.neo4j.graphdb.event.PropertyEntry;
import org.neo4j.graphdb.event.TransactionData;
import org.neo4j.graphdb.event.TransactionEventListener;
import org.neo4j.helpers.collection.Iterables;
import org.neo4j.kernel.api.KernelTransaction;
import org.neo4j.kernel.api.TransactionHook;
import org.neo4j.kernel.database.DatabaseId;
import org.neo4j.kernel.impl.coreapi.TxStateTransactionDataSnapshot;
import org.neo4j.kernel.impl.factory.GraphDatabaseFacade;
import org.neo4j.kernel.impl.transaction.events.GlobalTransactionEventListeners;
import org.neo4j.storageengine.api.StorageReader;
import org.neo4j.storageengine.api.txstate.ReadableTransactionState;

/**
 * Handle the collection of transaction event handlers, and fire events as needed.
 */
public class DatabaseTransactionEventListeners implements TransactionHook<DatabaseTransactionEventListeners.TransactionHandlerState>
{
    private final GlobalTransactionEventListeners globalTransactionEventListeners;
    private final DatabaseId databaseId;
    private final GraphDatabaseFacade databaseFacade;

    public DatabaseTransactionEventListeners( GraphDatabaseFacade databaseFacade, GlobalTransactionEventListeners globalTransactionEventListeners,
            DatabaseId databaseId )
    {
        this.databaseFacade = databaseFacade;
        this.globalTransactionEventListeners = globalTransactionEventListeners;
        this.databaseId = databaseId;
    }

    @Override
    public TransactionHandlerState beforeCommit( ReadableTransactionState state, KernelTransaction transaction,
            StorageReader storageReader )
    {
        // The iterator grabs a snapshot of our list of handlers
        Iterator<TransactionEventListener<?>> handlers = globalTransactionEventListeners.getDatabaseTransactionEventListeners( databaseId.name() ).iterator();
        if ( !handlers.hasNext() )
        {
            // Use 'null' as a signal that no event handlers were registered at beforeCommit time
            return null;
        }

        TransactionData txData = state == null ? EMPTY_DATA :
                new TxStateTransactionDataSnapshot( state, databaseFacade, storageReader, transaction );

        TransactionHandlerState handlerStates = new TransactionHandlerState( txData );
        while ( handlers.hasNext() )
        {
            TransactionEventListener<?> handler = handlers.next();
            try
            {
                handlerStates.add( handler ).setState( handler.beforeCommit( txData, databaseFacade ) );
            }
            catch ( Throwable t )
            {
                handlerStates.failed( t );
            }
        }

        return handlerStates;
    }

    @Override
    @SuppressWarnings( "unchecked" )
    public void afterCommit( ReadableTransactionState state,
            KernelTransaction transaction,
            TransactionHandlerState handlerState )
    {
        if ( handlerState == null )
        {
            // As per beforeCommit, 'null' means no handlers were registered in time for this transaction to
            // observe them.
            return;
        }

        try
        {
            for ( HandlerAndState handlerAndState : handlerState.states )
            {
                handlerAndState.handler.afterCommit( handlerState.txData, handlerAndState.state, databaseFacade );
            }
        }
        finally
        {
            if ( handlerState.txData instanceof TxStateTransactionDataSnapshot )
            {
                ((TxStateTransactionDataSnapshot) handlerState.txData).close();
            }
            // else if could be EMPTY_DATA as well, and we don't want the user-facing TransactionData interface to have close() on it
        }
    }

    @Override
    @SuppressWarnings( "unchecked" )
    public void afterRollback( ReadableTransactionState state,
            KernelTransaction transaction,
            TransactionHandlerState handlerState )
    {
        if ( handlerState == null )
        {
            // For legacy reasons, we don't call transaction handlers on implicit rollback.
            return;
        }

        for ( HandlerAndState handlerAndState : handlerState.states )
        {
            handlerAndState.handler.afterRollback( handlerState.txData, handlerAndState.state, databaseFacade );
        }
    }

    public static class HandlerAndState
    {
        private final TransactionEventListener handler;
        private Object state;

        HandlerAndState( TransactionEventListener<?> handler )
        {
            this.handler = handler;
        }

        void setState( Object state )
        {
            this.state = state;
        }
    }

    public static class TransactionHandlerState implements TransactionHook.Outcome
    {
        private final TransactionData txData;
        private final List<HandlerAndState> states = new LinkedList<>();
        private Throwable error;

        TransactionHandlerState( TransactionData txData )
        {
            this.txData = txData;
        }

        public void failed( Throwable error )
        {
            this.error = error;
        }

        @Override
        public boolean isSuccessful()
        {
            return error == null;
        }

        @Override
        public Throwable failure()
        {
            return error;
        }

        public HandlerAndState add( TransactionEventListener<?> handler )
        {
            HandlerAndState result = new HandlerAndState( handler );
            states.add( result );
            return result;
        }
    }

    private static final TransactionData EMPTY_DATA = new TransactionData()
    {
        @Override
        public Iterable<Node> createdNodes()
        {
            return Iterables.empty();
        }

        @Override
        public Iterable<Node> deletedNodes()
        {
            return Iterables.empty();
        }

        @Override
        public boolean isDeleted( Node node )
        {
            return false;
        }

        @Override
        public Iterable<PropertyEntry<Node>> assignedNodeProperties()
        {
            return Iterables.empty();
        }

        @Override
        public Iterable<PropertyEntry<Node>> removedNodeProperties()
        {
            return Iterables.empty();
        }

        @Override
        public Iterable<LabelEntry> assignedLabels()
        {
            return Iterables.empty();
        }

        @Override
        public Iterable<LabelEntry> removedLabels()
        {
            return Iterables.empty();
        }

        @Override
        public Iterable<Relationship> createdRelationships()
        {
            return Iterables.empty();
        }

        @Override
        public Iterable<Relationship> deletedRelationships()
        {
            return Iterables.empty();
        }

        @Override
        public boolean isDeleted( Relationship relationship )
        {
            return false;
        }

        @Override
        public String username()
        {
            return StringUtils.EMPTY;
        }

        @Override
        public Map<String,Object> metaData()
        {
            return Collections.emptyMap();
        }

        @Override
        public Iterable<PropertyEntry<Relationship>> assignedRelationshipProperties()
        {
            return Iterables.empty();
        }

        @Override
        public Iterable<PropertyEntry<Relationship>> removedRelationshipProperties()
        {
            return Iterables.empty();
        }
    };
}
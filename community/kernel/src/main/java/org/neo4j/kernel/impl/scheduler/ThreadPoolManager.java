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
package org.neo4j.kernel.impl.scheduler;

import java.util.concurrent.ConcurrentHashMap;

import org.neo4j.internal.helpers.Exceptions;
import org.neo4j.scheduler.Group;

final class ThreadPoolManager
{
    private final ConcurrentHashMap<Group,ThreadPool> pools;
    private final ThreadGroup topLevelGroup;

    ThreadPoolManager( ThreadGroup topLevelGroup )
    {
        this.topLevelGroup = topLevelGroup;
        pools = new ConcurrentHashMap<>();
    }

    ThreadPool getThreadPool( Group group, Integer desiredParallelism )
    {
        return pools.computeIfAbsent( group, g ->
        {
            if ( desiredParallelism == null )
            {
                return new ThreadPool( g, topLevelGroup );
            }
            else
            {
                return new ThreadPool( g, topLevelGroup, desiredParallelism );
            }
        } );
    }

    InterruptedException shutDownAll()
    {
        pools.forEach( ( group, pool ) -> pool.cancelAllJobs() );
        pools.forEach( ( group, pool ) -> pool.shutDown() );
        return pools.values().stream()
                    .map( ThreadPool::getShutdownException )
                    .reduce( null, Exceptions::chain );
    }
}

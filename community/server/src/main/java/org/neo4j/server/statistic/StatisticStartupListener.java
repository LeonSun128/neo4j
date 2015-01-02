/**
 * Copyright (c) 2002-2015 "Neo Technology,"
 * Network Engine for Objects in Lund AB [http://neotechnology.com]
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
package org.neo4j.server.statistic;

import org.mortbay.component.LifeCycle;
import org.mortbay.jetty.Handler;
import org.mortbay.jetty.Server;
import org.mortbay.jetty.servlet.Context;
import org.mortbay.jetty.servlet.FilterHolder;

import org.neo4j.kernel.logging.ConsoleLogger;
import org.neo4j.kernel.logging.Logging;

/**
 * @author tbaum
 * @since 23.06.11 22:04
 */
public class StatisticStartupListener implements LifeCycle.Listener
{
    private final Server jetty;
    private final FilterHolder holder;
    private final ConsoleLogger log;

    public StatisticStartupListener( Server jetty, StatisticFilter statisticFilter, Logging logging )
    {
        this.jetty = jetty;
        holder = new FilterHolder( statisticFilter );
        this.log = logging.getConsoleLog( getClass() );
    }

    @Override
    public void lifeCycleStarting( final LifeCycle event )
    {
    }

    @Override
    public void lifeCycleStarted( final LifeCycle event )
    {
        for ( Handler handler : jetty.getHandlers() )
        {
            if ( handler instanceof Context )
            {
                final Context context = (Context) handler;
                final String path = context.getContextPath();

                log.log( "adding statistic-filter to " + path );
                context.addFilter( holder, "/*", Handler.ALL );
            }
        }
    }

    @Override
    public void lifeCycleFailure( final LifeCycle event, final Throwable cause )
    {
    }

    @Override
    public void lifeCycleStopping( final LifeCycle event )
    {
        log.log( "stopping filter" );
        holder.doStop();
    }

    @Override
    public void lifeCycleStopped( final LifeCycle event )
    {
    }

    public void stop()
    {
        log.log( "stopping listeneer" );
        jetty.removeLifeCycleListener( this );
    }
}

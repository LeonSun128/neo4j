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
package org.neo4j.fabric.planning

import org.neo4j.cypher.internal.FullyParsedQuery
import org.neo4j.cypher.internal.util.ObfuscationMetadata
import org.neo4j.fabric.planning.FabricPlan.DebugOptions

case class FabricPlan(
  query: Fragment,
  queryType: QueryType,
  executionType: FabricPlan.ExecutionType,
  queryString: String,
  debugOptions: DebugOptions,
  obfuscationMetadata: ObfuscationMetadata,
  inFabricContext: Boolean
)

object FabricPlan {

  sealed trait ExecutionType
  case object Execute extends ExecutionType
  case object Explain extends ExecutionType
  case object Profile extends ExecutionType
  val EXECUTE: ExecutionType = Execute
  val EXPLAIN: ExecutionType = Explain
  val PROFILE: ExecutionType = Profile

  object DebugOptions {
    def from(debugOptions: Set[String]): DebugOptions = DebugOptions(
      logPlan = debugOptions.contains("fabriclogplan"),
      logRecords = debugOptions.contains("fabriclogrecords"),
    )
  }

  case class DebugOptions(
    logPlan: Boolean,
    logRecords: Boolean,
  )
}

sealed trait FabricQuery

object FabricQuery {

  final case class LocalQuery(
    query: FullyParsedQuery,
    queryType: QueryType,
  ) extends FabricQuery

  final case class RemoteQuery(
    query: String,
    queryType: QueryType,
    extractedLiterals: Map[String, Any],
  ) extends FabricQuery


}

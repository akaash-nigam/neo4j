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
package org.neo4j.cypher.internal.compiler.v2_3.ast.rewriters

import org.neo4j.cypher.internal.compiler.v2_3._
import ast._


case object addUniquenessPredicates extends Rewriter {

  def apply(that: AnyRef): AnyRef = instance(that)

  case class UniqueRel(identifier: Identifier, types: Set[RelTypeName]) {
    def name = identifier.name

    def isAlwaysDifferentFrom(other: UniqueRel) =
      types.nonEmpty && other.types.nonEmpty && (types intersect other.types).isEmpty
  }

  private val instance = replace(replacer => {
    case expr: Expression =>
        replacer.stop(expr)

    case m@Match(_, pattern: Pattern, _, where: Option[Where]) =>
      val uniqueRels: Seq[UniqueRel] = pattern.treeFold(Seq.empty[UniqueRel]) {
        case _: ShortestPaths =>
          (acc, _) => acc

        case RelationshipChain(_, RelationshipPattern(optR, _, types, None, _, _), _) =>
          (acc, children) => {
            val r = optR.getOrElse(throw new InternalException("This rewriter cannot work with unnamed patterns"))
            val rel = UniqueRel(r, types.toSet)
            children(acc :+ rel)
          }
      }

      if (uniqueRels.size < 2) {
        m
      } else {
        val maybePredicate: Option[Expression] = createPredicateFor(uniqueRels, m.position)
        val newWhere: Option[Where] = (where, maybePredicate) match {
          case (Some(oldWhere), Some(newPredicate)) =>
            Some(oldWhere.copy(expression = And(oldWhere.expression, newPredicate)(m.position))(m.position))

          case (None,           Some(newPredicate)) =>
            Some(Where(expression = newPredicate)(m.position))

          case (oldWhere,       None)               => oldWhere
        }
        m.copy(where = newWhere)(m.position)
      }

    case astNode =>
      replacer.expand(astNode)
  })

  private def createPredicateFor(uniqueRels: Seq[UniqueRel], pos: InputPosition): Option[Expression] = {
    val predicates: Seq[Expression] = for {
      x <- uniqueRels
      y <- uniqueRels if x.name < y.name && !x.isAlwaysDifferentFrom(y)
    } yield Not(Equals(x.identifier.copyId, y.identifier.copyId)(pos))(pos)

    predicates.reduceOption(And(_, _)(pos))
  }
}

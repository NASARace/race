/*
 * Copyright (c) 2020, United States Government, as represented by the
 * Administrator of the National Aeronautics and Space Administration.
 * All rights reserved.
 *
 * The RACE - Runtime for Airspace Concept Evaluation platform is licensed
 * under the Apache License, Version 2.0 (the "License"); you may not use
 * this file except in compliance with the License. You may obtain a copy
 * of the License at http://www.apache.org/licenses/LICENSE-2.0.
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package gov.nasa.race.http.tabdata

import gov.nasa.race.common.LogWriter

import scala.collection.immutable.{ListMap, Map}

/*
TODO - who compiles FieldExpressions (should also hold functionLibrary)
TODO - do we still need an EvalContext (if so UpdatePolicy has to implement it)
 */

/**
  * policy object to that defines ProviderData update semantics
  */
trait UpdatePolicy extends LogWriter {
  val fields: ListMap[String,Field]  // definition of fields and formulas

  /**
    * set and compute new fieldValues
    *
    * @param oldValues current values
    * @param changedValues explicitly changed values
    * @return new fieldValues
    */
  def update (oldValues: Map[String,FieldValue], changedValues: Seq[(String,FieldValue)]): Map[String,FieldValue]

  /**
    * compute fieldValues with formulas that do not have a value set
    */
  def initialize (values: Map[String,FieldValue]): Map[String,FieldValue]
}

/**
  * update policy that uses two phases:
  *   - phase 1: set explicitly changed field values
  *   - phase 2: evaluate all formulas that depend on changed values in order of field definition
  */
class PhasedInOrderUpdater (val fields: ListMap[String,Field]) extends UpdatePolicy {

  /**
    * note we do not assume changedValues are ordered in fields order
    */
  def update (oldValues: Map[String,FieldValue], changedValues: Seq[(String,FieldValue)]): Map[String,FieldValue] = {
    val ctx = new SimpleEvalContext(null,oldValues)

    //--- phase 1 - set all non-locked fields from changedValues (this does not have to be ordered)
    changedValues.foreach { cv=>
      val fieldId = cv._1
      val fieldVal = cv._2
      fields.get(fieldId) match {
        case Some(field) =>
          if (!field.isLocked) {
            info(s"updating field '$fieldId' <- '$fieldVal'")
            ctx.fieldValues = ctx.fieldValues + cv
          } else warning(s"ignoring update of locked field: '$fieldId'")

        case None => warning(s"ignoring update of unknown field: '$fieldId''")
      }
    }

    //--- phase 2 - evaluate all formulas of fields with changed dependencies and set their values accordingly
    fields.foreach { fe =>
      val field = fe._2
      if (field.hasDependencies && changedValues.exists( cv=> field.containsDependency(cv._1))){
        ctx.contextField = field
        field.evalWith(ctx) match {
          case Some(newVal) => ctx.fieldValues = ctx.fieldValues + (field.id -> newVal)
          case None => warning(s"field formula did not update: '$field.id'")
        }
      }
    }

    ctx.fieldValues
  }

  def initialize (oldValues: Map[String,FieldValue]): Map[String,FieldValue] = {
    val ctx = new SimpleEvalContext(null,oldValues)

    fields.foreach { fe =>  // has to be in order
      val fieldId = fe._1
      val field = fe._2
      if (field.hasFormula && !oldValues.contains(fieldId)) {
        ctx.contextField = field
        field.evalWith(ctx) match {
          case Some(newVal) => ctx.fieldValues = ctx.fieldValues + (field.id -> newVal)
          case None => warning(s"field formula did not update: '$field.id'")
        }
      }
    }

    ctx.fieldValues
  }

}
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
import gov.nasa.race.uom.DateTime

import scala.collection.immutable.{ListMap, Map}
import scala.collection.mutable.ArrayBuffer

/*
TODO - who compiles FieldExpressions (should also hold functionLibrary)
 */

/**
  * policy object that defines ProviderData update semantics
  */
trait FieldValueUpdater extends LogWriter {
  type ChangedFV = (String,FieldValue)

  val fields: ListMap[String,Field]  // ordered definition of fields (with respective formulas)

  /**
    * set/compute new fieldValues vertically (within pdc.providerIdColumn)
    *
    * @param oldData current values
    * @param pdc change to apply
    * @return tuple of additional changes and new fieldValues (plus Seq of eval-changed fields)
    */
  def update (oldValues: Map[String,FieldValue], pdc: ProviderDataChange, date: DateTime): (Seq[ChangedFV],Map[String,FieldValue])

  /**
    * compute fieldValues with formulas that do not have a value set
    */
  def initialize (oldValues: Map[String,FieldValue], date: DateTime): (Seq[ChangedFV],Map[String,FieldValue])
}

/**
  * update policy that uses two phases:
  *   - phase 1: set explicitly changed field values
  *   - phase 2: evaluate all formulas that depend on changed values in order of field definition
  */
class PhasedInOrderUpdater (val nodeId: String, val fields: ListMap[String,Field]) extends FieldValueUpdater {


  /**
    * note we do not assume changedValues are ordered in fields order - we update in order of field definition
    * in the catalog
    *
    * note also that we take previous eval changes into account to determine if we have to eval a field formula
    * (formulas can depend on computed fields)
    */
  def update (oldValues: Map[String,FieldValue], pdc: ProviderDataChange, date: DateTime): (Seq[ChangedFV],Map[String,FieldValue]) = {

    val evalChangedValues = ArrayBuffer.empty[ChangedFV] // what we change ourselves evaluating formulas

    def needsEval(field: Field): Boolean = {
      !pdc.fieldValues.exists( _._1 == field.id) &&   // field was not set in this change
        (field.containsAnyDependency(pdc.fieldValues) || field.containsAnyDependency(evalChangedValues))
    }

    val ctx = new SimpleEvalContext(null,oldValues,date)

    //--- phase 1 - set all non-locked fields from changedValues (this does not have to be ordered)
    pdc.fieldValues.foreach { cv=>
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

    //--- phase 2 - in-order evaluate all formulas of fields with changed dependencies and set their values accordingly
    fields.foreach { fe =>
      val field = fe._2
      if (needsEval(field)){
        ctx.contextField = field
        field.evalWith(ctx) match {
          case Some(newVal) =>
            val fieldValueChange = (field.id -> newVal)
            ctx.fieldValues = ctx.fieldValues + fieldValueChange
            evalChangedValues += fieldValueChange
          case None => warning(s"field formula did not update: '${field.id}'")
        }
      }
    }

    (evalChangedValues.toSeq, ctx.fieldValues)
  }

  /**
    * evaluate all formula for which we don't have field values but all dependencies resolve
    */
  def initialize (oldValues: Map[String,FieldValue], date: DateTime): (Seq[ChangedFV],Map[String,FieldValue]) = {
    val ctx = new SimpleEvalContext(null,oldValues,date)
    val evalChangedValues = ArrayBuffer.empty[ChangedFV] // what we change ourselves evaluating formulas

    fields.foreach { fe =>
      val field = fe._2
      if (field.hasFormula && !oldValues.contains(field.id) && field.hasAllDependencies(ctx.fieldValues)){
        ctx.contextField = field
        field.evalWith(ctx) match {
          case Some(newVal) =>
            val fieldValueChange = (field.id -> newVal)
            ctx.fieldValues = ctx.fieldValues + fieldValueChange
            evalChangedValues += fieldValueChange
          case None => warning(s"field formula did not update: '$field.id'")
        }
      }
    }
    (evalChangedValues.toSeq, ctx.fieldValues)
  }

}
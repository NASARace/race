/*
 * Copyright (c) 2016, United States Government, as represented by the
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

/*
 * Copyright (c) 2016, United States Government, as represented by the 
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

package gov.nasa.race.common

import com.github.nscala_time.time.Imports._
import org.joda.time.DateTime

/**
  * a trait for objects that can adjust date based on delta between a original (first)
  * date and a dynamic base date
  *
  * primary use case are objects that have to provide some replay capability in a changed time
  * context
  */
trait DateAdjuster {
  var baseDate: DateTime = _
  var firstDate: DateTime = _

  def setBaseDate(date: DateTime) = baseDate = date

  def getDate(date: DateTime): DateTime = {
    if (baseDate == null) {
      date
    } else {
      if (firstDate == null) {
        firstDate = date
        baseDate
      } else {
        if (date > firstDate) {
          baseDate + (firstDate to date).toDurationMillis
        } else {
          baseDate - (date to firstDate).toDurationMillis
        }
      }
    }
  }

  def getDate(millis: Long): DateTime = getDate(new DateTime(millis))
}

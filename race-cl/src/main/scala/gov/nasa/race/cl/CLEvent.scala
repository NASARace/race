/*
 * Copyright (c) 2017, United States Government, as represented by the
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
package gov.nasa.race.cl

import CLUtils._
import org.lwjgl.opencl.CL10._
import org.lwjgl.opencl.{CL11, CLEventCallbackI}

/**
  * wrapper for OpenCL event object
  */
class CLEvent (val id: Long, val context: CLContext) extends CLResource {

  protected def release: Unit = clReleaseEvent(id).? // required for CLResources

  def setCompletionAction (action: =>Unit): Unit = {
    val cb = new CLEventCallbackI {
      def invoke (eid: Long, exec_status: Int, user_data: Long): Unit = {
        action
        close
      }
    }
    CL11.clSetEventCallback(id, CL_COMPLETE,cb, 0).?
  }
}

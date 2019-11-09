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

package gov.nasa.race.util;

import java.util.function.Function;

/**
 * utilities for runtime optimizations that are difficult in Scala
 */
public class JUtils {

    /**
     * iterate over array of objects without creating an iterator object itself
     * note this still incurs the cost of an extra function invocation per array element
     *
     * note also that you need to have a implicit conversion to a java.util.function.Function
     * in scope if this is used with Scala lambdas
     */
    public static <A> void iterateOver (A[] array, Function<A,?> body) {
        for (int i=0; i<array.length; i++){
            body.apply(array[i]);
        }
    }

    @SuppressWarnings("deprecation")
    public static void getASCIIBytes(String s, int srcBegin, int srcEnd, byte[] dst, int dstBegin) {
        s.getBytes(srcBegin,srcEnd,dst,dstBegin);
    }
}

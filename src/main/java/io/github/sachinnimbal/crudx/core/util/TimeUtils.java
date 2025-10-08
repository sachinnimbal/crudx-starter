/*
 * Copyright 2025 Sachin Nimbal
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.github.sachinnimbal.crudx.core.util;

/**
 * @author Sachin Nimbal
 * @see <a href="https://www.linkedin.com/in/sachin-nimbal/">LinkedIn Profile</a>
 */
public class TimeUtils {

    private TimeUtils() {}

    public static String formatExecutionTime(long milliseconds) {
        if (milliseconds < 1000) {
            return milliseconds + " ms";
        } else if (milliseconds < 60000) {
            double seconds = milliseconds / 1000.0;
            return String.format("%.2fs (%d ms)", seconds, milliseconds);
        } else if (milliseconds < 3600000) {
            long minutes = milliseconds / 60000;
            long seconds = (milliseconds % 60000) / 1000;
            return String.format("%dm %ds (%d ms)", minutes, seconds, milliseconds);
        } else {
            long hours = milliseconds / 3600000;
            long minutes = (milliseconds % 3600000) / 60000;
            long seconds = (milliseconds % 60000) / 1000;
            return String.format("%dh %dm %ds (%d ms)", hours, minutes, seconds, milliseconds);
        }
    }
}

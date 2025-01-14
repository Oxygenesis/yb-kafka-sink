/*
 * Copyright 2018 Confluent Inc.
 *
 * Licensed under the Confluent Community License (the "License"); you may not use
 * this file except in compliance with the License.  You may obtain a copy of the
 * License at
 *
 * http://www.confluent.io/confluent-community-license
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OF ANY KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations under the License.
 */

package com.yugabyte.jdbc.util;

import java.text.SimpleDateFormat;
import java.util.*;

public class DateTimeUtils {

  private static final ThreadLocal<Map<TimeZone, Calendar>> TIMEZONE_CALENDARS =
      ThreadLocal.withInitial(HashMap::new);

  private static final ThreadLocal<Map<TimeZone, SimpleDateFormat>> TIMEZONE_DATE_FORMATS =
      ThreadLocal.withInitial(HashMap::new);

  private static final ThreadLocal<Map<TimeZone, SimpleDateFormat>> TIMEZONE_TIME_FORMATS =
      ThreadLocal.withInitial(HashMap::new);

  private static final ThreadLocal<Map<TimeZone, SimpleDateFormat>> TIMEZONE_TIMESTAMP_FORMATS =
      ThreadLocal.withInitial(HashMap::new);

  public static Calendar getTimeZoneCalendar(final TimeZone timeZone) {
    return TIMEZONE_CALENDARS.get().computeIfAbsent(timeZone, GregorianCalendar::new);
  }

  public static String formatDate(Date date, TimeZone timeZone) {
    return TIMEZONE_DATE_FORMATS
        .get()
        .computeIfAbsent(
            timeZone,
            aTimeZone -> {
              SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd");
              sdf.setTimeZone(aTimeZone);
              return sdf;
            })
        .format(date);
  }

  public static String formatTime(Date date, TimeZone timeZone) {
    return TIMEZONE_TIME_FORMATS
        .get()
        .computeIfAbsent(
            timeZone,
            aTimeZone -> {
              SimpleDateFormat sdf = new SimpleDateFormat("HH:mm:ss.SSS");
              sdf.setTimeZone(aTimeZone);
              return sdf;
            })
        .format(date);
  }

  public static String formatTimestamp(Date date, TimeZone timeZone) {
    return TIMEZONE_TIMESTAMP_FORMATS
        .get()
        .computeIfAbsent(
            timeZone,
            aTimeZone -> {
              SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS");
              sdf.setTimeZone(aTimeZone);
              return sdf;
            })
        .format(date);
  }

  private DateTimeUtils() {}
}

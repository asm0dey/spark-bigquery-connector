/*
 * Copyright 2018 Google Inc. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.google.cloud.spark.bigquery.spark3;

import com.google.cloud.spark.bigquery.DataFrameToRDDConverter;
import java.io.Serializable;
import java.util.function.Function;
import org.apache.spark.rdd.RDD;
import org.apache.spark.sql.Dataset;
import org.apache.spark.sql.Row;
import org.apache.spark.sql.SparkSqlUtils;
import org.apache.spark.sql.catalyst.InternalRow;
import org.apache.spark.sql.catalyst.encoders.ExpressionEncoder;
import org.apache.spark.sql.catalyst.encoders.ExpressionEncoder.Deserializer;
import org.apache.spark.sql.types.StructType;
import scala.collection.Iterator;
import scala.reflect.ClassTag;
import scala.reflect.ClassTag$;
import scala.runtime.AbstractFunction1;

public class Spark3DataFrameToRDDConverter implements DataFrameToRDDConverter {

  @Override
  public RDD<Row> convertToRDD(Dataset<Row> data) {
    StructType schema = data.schema();
    final ExpressionEncoder<Row> expressionEncoder =
        SparkSqlUtils.getInstance().createExpressionEncoder(schema);
    final Deserializer<Row> deserializer = expressionEncoder.createDeserializer();
    ClassTag<Row> classTag = ClassTag$.MODULE$.apply(Row.class);

    RDD<Row> rowRdd =
        data.queryExecution()
            .toRdd()
            .mapPartitions(getIteratorMapper(deserializer), false, classTag);

    return rowRdd;
  }

  @Override
  public boolean supports(String sparkVersion) {
    return sparkVersion.compareTo("3") >= 0;
  }

  private AbstractFunction1<Iterator<InternalRow>, Iterator<Row>> getIteratorMapper(
      final Deserializer<Row> deserializer) {

    final AbstractFunction1<InternalRow, Row> internalRowMapper =
        new SerializableAbstractFunction1(
            (Function<InternalRow, Row> & Serializable)
                internalRow -> deserializer.apply(internalRow));

    final AbstractFunction1<Iterator<InternalRow>, Iterator<Row>> iteratorMapper =
        new SerializableAbstractFunction1(
            (Function<Iterator<InternalRow>, Iterator<Row>> & Serializable)
                iterator -> iterator.map(internalRowMapper));

    return iteratorMapper;
  }
}

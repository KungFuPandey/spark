/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.spark.sql

import java.beans.Introspector
import java.util.Properties

import scala.collection.immutable
import scala.collection.JavaConverters._
import scala.reflect.ClassTag
import scala.reflect.runtime.universe.TypeTag
import scala.util.control.NonFatal

import org.apache.spark.{SparkConf, SparkContext}
import org.apache.spark.annotation.{DeveloperApi, Experimental}
import org.apache.spark.api.java.JavaRDD
import org.apache.spark.internal.config.{CATALOG_IMPLEMENTATION, ConfigEntry}
import org.apache.spark.rdd.RDD
import org.apache.spark.sql.catalog.Catalog
import org.apache.spark.sql.catalyst._
import org.apache.spark.sql.catalyst.catalog._
import org.apache.spark.sql.catalyst.encoders._
import org.apache.spark.sql.catalyst.expressions.AttributeReference
import org.apache.spark.sql.catalyst.plans.logical.{LocalRelation, LogicalPlan, Range}
import org.apache.spark.sql.execution._
import org.apache.spark.sql.execution.datasources.LogicalRelation
import org.apache.spark.sql.execution.ui.SQLListener
import org.apache.spark.sql.internal.{CatalogImpl, SessionState, SharedState}
import org.apache.spark.sql.sources.BaseRelation
import org.apache.spark.sql.types.{DataType, LongType, StructType}
import org.apache.spark.sql.util.ExecutionListenerManager
import org.apache.spark.util.Utils


/**
 * The entry point to Spark execution.
 */
class SparkSession private(
    @transient val sparkContext: SparkContext,
    @transient private val existingSharedState: Option[SharedState])
  extends Serializable { self =>

  def this(sc: SparkContext) {
    this(sc, None)
  }


  /* ----------------------- *
   |  Session-related state  |
   * ----------------------- */

  /**
   * State shared across sessions, including the [[SparkContext]], cached data, listener,
   * and a catalog that interacts with external systems.
   */
  @transient
  protected[sql] lazy val sharedState: SharedState = {
    existingSharedState.getOrElse(
      SparkSession.reflect[SharedState, SparkContext](
        SparkSession.sharedStateClassName(sparkContext.conf),
        sparkContext))
  }

  /**
   * State isolated across sessions, including SQL configurations, temporary tables, registered
   * functions, and everything else that accepts a [[org.apache.spark.sql.internal.SQLConf]].
   */
  @transient
  protected[sql] lazy val sessionState: SessionState = {
    SparkSession.reflect[SessionState, SparkSession](
      SparkSession.sessionStateClassName(sparkContext.conf),
      self)
  }

  /**
   * A wrapped version of this session in the form of a [[SQLContext]].
   */
  @transient
  private var _wrapped: SQLContext = _

  protected[sql] def wrapped: SQLContext = {
    if (_wrapped == null) {
      _wrapped = new SQLContext(self, isRootContext = false)
    }
    _wrapped
  }

  protected[sql] def setWrappedContext(sqlContext: SQLContext): Unit = {
    _wrapped = sqlContext
  }

  protected[sql] def cacheManager: CacheManager = sharedState.cacheManager
  protected[sql] def listener: SQLListener = sharedState.listener
  protected[sql] def externalCatalog: ExternalCatalog = sharedState.externalCatalog

  /**
   * :: Experimental ::
   * An interface to register custom [[org.apache.spark.sql.util.QueryExecutionListener]]s
   * that listen for execution metrics.
   *
   * @group basic
   * @since 2.0.0
   */
  @Experimental
  def listenerManager: ExecutionListenerManager = sessionState.listenerManager

  /**
   * :: Experimental ::
   * A collection of methods that are considered experimental, but can be used to hook into
   * the query planner for advanced functionality.
   *
   * @group basic
   * @since 2.0.0
   */
  @Experimental
  def experimental: ExperimentalMethods = sessionState.experimentalMethods

  /**
   * A collection of methods for registering user-defined functions (UDF).
   *
   * The following example registers a Scala closure as UDF:
   * {{{
   *   sparkSession.udf.register("myUDF", (arg1: Int, arg2: String) => arg2 + arg1)
   * }}}
   *
   * The following example registers a UDF in Java:
   * {{{
   *   sparkSession.udf().register("myUDF",
   *       new UDF2<Integer, String, String>() {
   *           @Override
   *           public String call(Integer arg1, String arg2) {
   *               return arg2 + arg1;
   *           }
   *      }, DataTypes.StringType);
   * }}}
   *
   * Or, to use Java 8 lambda syntax:
   * {{{
   *   sparkSession.udf().register("myUDF",
   *       (Integer arg1, String arg2) -> arg2 + arg1,
   *       DataTypes.StringType);
   * }}}
   *
   * @group basic
   * @since 2.0.0
   */
  def udf: UDFRegistration = sessionState.udf

  /**
   * Returns a [[ContinuousQueryManager]] that allows managing all the
   * [[org.apache.spark.sql.ContinuousQuery ContinuousQueries]] active on `this`.
   *
   * @group basic
   * @since 2.0.0
   */
  def streams: ContinuousQueryManager = sessionState.continuousQueryManager

  /**
   * Start a new session with isolated SQL configurations, temporary tables, registered
   * functions are isolated, but sharing the underlying [[SparkContext]] and cached data.
   *
   * Note: Other than the [[SparkContext]], all shared state is initialized lazily.
   * This method will force the initialization of the shared state to ensure that parent
   * and child sessions are set up with the same shared state. If the underlying catalog
   * implementation is Hive, this will initialize the metastore, which may take some time.
   *
   * @group basic
   * @since 2.0.0
   */
  def newSession(): SparkSession = {
    new SparkSession(sparkContext, Some(sharedState))
  }


  /* -------------------------------------------------- *
   |  Methods for accessing or mutating configurations  |
   * -------------------------------------------------- */

  /**
   * Runtime configuration interface for Spark.
   *
   * This is the interface through which the user can get and set all Spark and Hadoop
   * configurations that are relevant to Spark SQL. When getting the value of a config,
   * this defaults to the value set in the underlying [[SparkContext]], if any.
   *
   * @group config
   * @since 2.0.0
   */
  @transient lazy val conf: RuntimeConfig = new RuntimeConfig(sessionState.conf)

  /**
   * Set Spark SQL configuration properties.
   *
   * @group config
   * @since 2.0.0
   */
  def setConf(props: Properties): Unit = sessionState.setConf(props)

  /**
   * Set the given Spark SQL configuration property.
   *
   * @group config
   * @since 2.0.0
   */
  def setConf(key: String, value: String): Unit = sessionState.setConf(key, value)

  /**
   * Return the value of Spark SQL configuration property for the given key.
   *
   * @group config
   * @since 2.0.0
   */
  def getConf(key: String): String = sessionState.conf.getConfString(key)

  /**
   * Return the value of Spark SQL configuration property for the given key. If the key is not set
   * yet, return `defaultValue`.
   *
   * @group config
   * @since 2.0.0
   */
  def getConf(key: String, defaultValue: String): String = {
    sessionState.conf.getConfString(key, defaultValue)
  }

  /**
   * Return all the configuration properties that have been set (i.e. not the default).
   * This creates a new copy of the config properties in the form of a Map.
   *
   * @group config
   * @since 2.0.0
   */
  def getAllConfs: immutable.Map[String, String] = sessionState.conf.getAllConfs

  /**
   * Set the given Spark SQL configuration property.
   */
  protected[sql] def setConf[T](entry: ConfigEntry[T], value: T): Unit = {
    sessionState.setConf(entry, value)
  }

  /**
   * Return the value of Spark SQL configuration property for the given key. If the key is not set
   * yet, return `defaultValue` in [[ConfigEntry]].
   */
  protected[sql] def getConf[T](entry: ConfigEntry[T]): T = sessionState.conf.getConf(entry)

  /**
   * Return the value of Spark SQL configuration property for the given key. If the key is not set
   * yet, return `defaultValue`. This is useful when `defaultValue` in ConfigEntry is not the
   * desired one.
   */
  protected[sql] def getConf[T](entry: ConfigEntry[T], defaultValue: T): T = {
    sessionState.conf.getConf(entry, defaultValue)
  }


  /* --------------------------------- *
   |  Methods for creating DataFrames  |
   * --------------------------------- */

  /**
   * :: Experimental ::
   * Returns a [[DataFrame]] with no rows or columns.
   *
   * @group dataframes
   * @since 2.0.0
   */
  @Experimental
  @transient
  lazy val emptyDataFrame: DataFrame = {
    createDataFrame(sparkContext.emptyRDD[Row], StructType(Nil))
  }

  /**
   * :: Experimental ::
   * Creates a [[DataFrame]] from an RDD of Product (e.g. case classes, tuples).
   *
   * @group dataframes
   * @since 2.0.0
   */
  @Experimental
  def createDataFrame[A <: Product : TypeTag](rdd: RDD[A]): DataFrame = {
    SQLContext.setActive(wrapped)
    val schema = ScalaReflection.schemaFor[A].dataType.asInstanceOf[StructType]
    val attributeSeq = schema.toAttributes
    val rowRDD = RDDConversions.productToRowRdd(rdd, schema.map(_.dataType))
    Dataset.ofRows(self, LogicalRDD(attributeSeq, rowRDD)(self))
  }

  /**
   * :: Experimental ::
   * Creates a [[DataFrame]] from a local Seq of Product.
   *
   * @group dataframes
   * @since 2.0.0
   */
  @Experimental
  def createDataFrame[A <: Product : TypeTag](data: Seq[A]): DataFrame = {
    SQLContext.setActive(wrapped)
    val schema = ScalaReflection.schemaFor[A].dataType.asInstanceOf[StructType]
    val attributeSeq = schema.toAttributes
    Dataset.ofRows(self, LocalRelation.fromProduct(attributeSeq, data))
  }

  /**
   * :: DeveloperApi ::
   * Creates a [[DataFrame]] from an [[RDD]] containing [[Row]]s using the given schema.
   * It is important to make sure that the structure of every [[Row]] of the provided RDD matches
   * the provided schema. Otherwise, there will be runtime exception.
   * Example:
   * {{{
   *  import org.apache.spark.sql._
   *  import org.apache.spark.sql.types._
   *  val sparkSession = new org.apache.spark.sql.SparkSession(sc)
   *
   *  val schema =
   *    StructType(
   *      StructField("name", StringType, false) ::
   *      StructField("age", IntegerType, true) :: Nil)
   *
   *  val people =
   *    sc.textFile("examples/src/main/resources/people.txt").map(
   *      _.split(",")).map(p => Row(p(0), p(1).trim.toInt))
   *  val dataFrame = sparkSession.createDataFrame(people, schema)
   *  dataFrame.printSchema
   *  // root
   *  // |-- name: string (nullable = false)
   *  // |-- age: integer (nullable = true)
   *
   *  dataFrame.registerTempTable("people")
   *  sparkSession.sql("select name from people").collect.foreach(println)
   * }}}
   *
   * @group dataframes
   * @since 2.0.0
   */
  @DeveloperApi
  def createDataFrame(rowRDD: RDD[Row], schema: StructType): DataFrame = {
    createDataFrame(rowRDD, schema, needsConversion = true)
  }

  /**
   * :: DeveloperApi ::
   * Creates a [[DataFrame]] from an [[JavaRDD]] containing [[Row]]s using the given schema.
   * It is important to make sure that the structure of every [[Row]] of the provided RDD matches
   * the provided schema. Otherwise, there will be runtime exception.
   *
   * @group dataframes
   * @since 2.0.0
   */
  @DeveloperApi
  def createDataFrame(rowRDD: JavaRDD[Row], schema: StructType): DataFrame = {
    createDataFrame(rowRDD.rdd, schema)
  }

  /**
   * :: DeveloperApi ::
   * Creates a [[DataFrame]] from an [[java.util.List]] containing [[Row]]s using the given schema.
   * It is important to make sure that the structure of every [[Row]] of the provided List matches
   * the provided schema. Otherwise, there will be runtime exception.
   *
   * @group dataframes
   * @since 2.0.0
   */
  @DeveloperApi
  def createDataFrame(rows: java.util.List[Row], schema: StructType): DataFrame = {
    Dataset.ofRows(self, LocalRelation.fromExternalRows(schema.toAttributes, rows.asScala))
  }

  /**
   * Applies a schema to an RDD of Java Beans.
   *
   * WARNING: Since there is no guaranteed ordering for fields in a Java Bean,
   * SELECT * queries will return the columns in an undefined order.
   *
   * @group dataframes
   * @since 2.0.0
   */
  def createDataFrame(rdd: RDD[_], beanClass: Class[_]): DataFrame = {
    val attributeSeq: Seq[AttributeReference] = getSchema(beanClass)
    val className = beanClass.getName
    val rowRdd = rdd.mapPartitions { iter =>
    // BeanInfo is not serializable so we must rediscover it remotely for each partition.
      val localBeanInfo = Introspector.getBeanInfo(Utils.classForName(className))
      SQLContext.beansToRows(iter, localBeanInfo, attributeSeq)
    }
    Dataset.ofRows(self, LogicalRDD(attributeSeq, rowRdd)(self))
  }

  /**
   * Applies a schema to an RDD of Java Beans.
   *
   * WARNING: Since there is no guaranteed ordering for fields in a Java Bean,
   * SELECT * queries will return the columns in an undefined order.
   *
   * @group dataframes
   * @since 2.0.0
   */
  def createDataFrame(rdd: JavaRDD[_], beanClass: Class[_]): DataFrame = {
    createDataFrame(rdd.rdd, beanClass)
  }

  /**
   * Applies a schema to an List of Java Beans.
   *
   * WARNING: Since there is no guaranteed ordering for fields in a Java Bean,
   *          SELECT * queries will return the columns in an undefined order.
   * @group dataframes
   * @since 1.6.0
   */
  def createDataFrame(data: java.util.List[_], beanClass: Class[_]): DataFrame = {
    val attrSeq = getSchema(beanClass)
    val beanInfo = Introspector.getBeanInfo(beanClass)
    val rows = SQLContext.beansToRows(data.asScala.iterator, beanInfo, attrSeq)
    Dataset.ofRows(self, LocalRelation(attrSeq, rows.toSeq))
  }

  /**
   * Convert a [[BaseRelation]] created for external data sources into a [[DataFrame]].
   *
   * @group dataframes
   * @since 2.0.0
   */
  def baseRelationToDataFrame(baseRelation: BaseRelation): DataFrame = {
    Dataset.ofRows(self, LogicalRelation(baseRelation))
  }

  def createDataset[T : Encoder](data: Seq[T]): Dataset[T] = {
    val enc = encoderFor[T]
    val attributes = enc.schema.toAttributes
    val encoded = data.map(d => enc.toRow(d).copy())
    val plan = new LocalRelation(attributes, encoded)
    Dataset[T](self, plan)
  }

  def createDataset[T : Encoder](data: RDD[T]): Dataset[T] = {
    val enc = encoderFor[T]
    val attributes = enc.schema.toAttributes
    val encoded = data.map(d => enc.toRow(d))
    val plan = LogicalRDD(attributes, encoded)(self)
    Dataset[T](self, plan)
  }

  def createDataset[T : Encoder](data: java.util.List[T]): Dataset[T] = {
    createDataset(data.asScala)
  }

  /**
   * :: Experimental ::
   * Creates a [[Dataset]] with a single [[LongType]] column named `id`, containing elements
   * in an range from 0 to `end` (exclusive) with step value 1.
   *
   * @since 2.0.0
   * @group dataset
   */
  @Experimental
  def range(end: Long): Dataset[java.lang.Long] = range(0, end)

  /**
   * :: Experimental ::
   * Creates a [[Dataset]] with a single [[LongType]] column named `id`, containing elements
   * in an range from `start` to `end` (exclusive) with step value 1.
   *
   * @since 2.0.0
   * @group dataset
   */
  @Experimental
  def range(start: Long, end: Long): Dataset[java.lang.Long] = {
    range(start, end, step = 1, numPartitions = sparkContext.defaultParallelism)
  }

  /**
   * :: Experimental ::
   * Creates a [[Dataset]] with a single [[LongType]] column named `id`, containing elements
   * in an range from `start` to `end` (exclusive) with an step value.
   *
   * @since 2.0.0
   * @group dataset
   */
  @Experimental
  def range(start: Long, end: Long, step: Long): Dataset[java.lang.Long] = {
    range(start, end, step, numPartitions = sparkContext.defaultParallelism)
  }

  /**
   * :: Experimental ::
   * Creates a [[Dataset]] with a single [[LongType]] column named `id`, containing elements
   * in an range from `start` to `end` (exclusive) with an step value, with partition number
   * specified.
   *
   * @since 2.0.0
   * @group dataset
   */
  @Experimental
  def range(start: Long, end: Long, step: Long, numPartitions: Int): Dataset[java.lang.Long] = {
    new Dataset(self, Range(start, end, step, numPartitions), Encoders.LONG)
  }

  /**
   * Creates a [[DataFrame]] from an RDD[Row].
   * User can specify whether the input rows should be converted to Catalyst rows.
   */
  protected[sql] def internalCreateDataFrame(
      catalystRows: RDD[InternalRow],
      schema: StructType): DataFrame = {
    // TODO: use MutableProjection when rowRDD is another DataFrame and the applied
    // schema differs from the existing schema on any field data type.
    val logicalPlan = LogicalRDD(schema.toAttributes, catalystRows)(self)
    Dataset.ofRows(self, logicalPlan)
  }

  /**
   * Creates a [[DataFrame]] from an RDD[Row].
   * User can specify whether the input rows should be converted to Catalyst rows.
   */
  protected[sql] def createDataFrame(
      rowRDD: RDD[Row],
      schema: StructType,
      needsConversion: Boolean) = {
    // TODO: use MutableProjection when rowRDD is another DataFrame and the applied
    // schema differs from the existing schema on any field data type.
    val catalystRows = if (needsConversion) {
      val converter = CatalystTypeConverters.createToCatalystConverter(schema)
      rowRDD.map(converter(_).asInstanceOf[InternalRow])
    } else {
      rowRDD.map{r: Row => InternalRow.fromSeq(r.toSeq)}
    }
    val logicalPlan = LogicalRDD(schema.toAttributes, catalystRows)(self)
    Dataset.ofRows(self, logicalPlan)
  }


  /* ------------------------ *
   |  Catalog-related methods |
   * ----------------- ------ */

  /**
   * Interface through which the user may create, drop, alter or query underlying
   * databases, tables, functions etc.
   *
   * @group ddl_ops
   * @since 2.0.0
   */
  @transient lazy val catalog: Catalog = new CatalogImpl(self)

  /**
   * Returns the specified table as a [[DataFrame]].
   *
   * @group ddl_ops
   * @since 2.0.0
   */
  def table(tableName: String): DataFrame = {
    table(sessionState.sqlParser.parseTableIdentifier(tableName))
  }

  protected[sql] def table(tableIdent: TableIdentifier): DataFrame = {
    Dataset.ofRows(self, sessionState.catalog.lookupRelation(tableIdent))
  }

  /**
   * Registers the given [[DataFrame]] as a temporary table in the catalog.
   * Temporary tables exist only during the lifetime of this instance of [[SparkSession]].
   */
  protected[sql] def registerDataFrameAsTable(df: DataFrame, tableName: String): Unit = {
    sessionState.catalog.createTempTable(
      sessionState.sqlParser.parseTableIdentifier(tableName).table,
      df.logicalPlan,
      overrideIfExists = true)
  }


  /* ----------------- *
   |  Everything else  |
   * ----------------- */

  /**
   * Executes a SQL query using Spark, returning the result as a [[DataFrame]].
   * The dialect that is used for SQL parsing can be configured with 'spark.sql.dialect'.
   *
   * @group basic
   * @since 2.0.0
   */
  def sql(sqlText: String): DataFrame = {
    Dataset.ofRows(self, parseSql(sqlText))
  }

  /**
   * :: Experimental ::
   * Returns a [[DataFrameReader]] that can be used to read data and streams in as a [[DataFrame]].
   * {{{
   *   sparkSession.read.parquet("/path/to/file.parquet")
   *   sparkSession.read.schema(schema).json("/path/to/file.json")
   * }}}
   *
   * @group genericdata
   * @since 2.0.0
   */
  @Experimental
  def read: DataFrameReader = new DataFrameReader(self)


  // scalastyle:off
  // Disable style checker so "implicits" object can start with lowercase i
  /**
   * :: Experimental ::
   * (Scala-specific) Implicit methods available in Scala for converting
   * common Scala objects into [[DataFrame]]s.
   *
   * {{{
   *   val sparkSession = new SparkSession(sc)
   *   import sparkSession.implicits._
   * }}}
   *
   * @group basic
   * @since 2.0.0
   */
  @Experimental
  object implicits extends SQLImplicits with Serializable {
    protected override def _sqlContext: SQLContext = wrapped
  }
  // scalastyle:on

  protected[sql] def parseSql(sql: String): LogicalPlan = {
    sessionState.sqlParser.parsePlan(sql)
  }

  protected[sql] def executeSql(sql: String): QueryExecution = {
    executePlan(parseSql(sql))
  }

  protected[sql] def executePlan(plan: LogicalPlan): QueryExecution = {
    sessionState.executePlan(plan)
  }

  /**
   * Executes a SQL query without parsing it, but instead passing it directly to an underlying
   * system to process. This is currently only used for Hive DDLs and will be removed as soon
   * as Spark can parse all supported Hive DDLs itself.
   */
  protected[sql] def runNativeSql(sqlText: String): Seq[Row] = {
    sessionState.runNativeSql(sqlText).map { r => Row(r) }
  }

  /**
   * Parses the data type in our internal string representation. The data type string should
   * have the same format as the one generated by `toString` in scala.
   * It is only used by PySpark.
   */
  protected[sql] def parseDataType(dataTypeString: String): DataType = {
    DataType.fromJson(dataTypeString)
  }

  /**
   * Apply a schema defined by the schemaString to an RDD. It is only used by PySpark.
   */
  protected[sql] def applySchemaToPythonRDD(
      rdd: RDD[Array[Any]],
      schemaString: String): DataFrame = {
    val schema = parseDataType(schemaString).asInstanceOf[StructType]
    applySchemaToPythonRDD(rdd, schema)
  }

  /**
   * Apply a schema defined by the schema to an RDD. It is only used by PySpark.
   */
  protected[sql] def applySchemaToPythonRDD(
      rdd: RDD[Array[Any]],
      schema: StructType): DataFrame = {
    val rowRdd = rdd.map(r => python.EvaluatePython.fromJava(r, schema).asInstanceOf[InternalRow])
    Dataset.ofRows(self, LogicalRDD(schema.toAttributes, rowRdd)(self))
  }

  /**
   * Returns a Catalyst Schema for the given java bean class.
   */
  private def getSchema(beanClass: Class[_]): Seq[AttributeReference] = {
    val (dataType, _) = JavaTypeInference.inferDataType(beanClass)
    dataType.asInstanceOf[StructType].fields.map { f =>
      AttributeReference(f.name, f.dataType, f.nullable)()
    }
  }

}


object SparkSession {

  private val HIVE_SHARED_STATE_CLASS_NAME = "org.apache.spark.sql.hive.HiveSharedState"
  private val HIVE_SESSION_STATE_CLASS_NAME = "org.apache.spark.sql.hive.HiveSessionState"

  private def sharedStateClassName(conf: SparkConf): String = {
    conf.get(CATALOG_IMPLEMENTATION) match {
      case "hive" => HIVE_SHARED_STATE_CLASS_NAME
      case "in-memory" => classOf[SharedState].getCanonicalName
    }
  }

  private def sessionStateClassName(conf: SparkConf): String = {
    conf.get(CATALOG_IMPLEMENTATION) match {
      case "hive" => HIVE_SESSION_STATE_CLASS_NAME
      case "in-memory" => classOf[SessionState].getCanonicalName
    }
  }

  /**
   * Helper method to create an instance of [[T]] using a single-arg constructor that
   * accepts an [[Arg]].
   */
  private def reflect[T, Arg <: AnyRef](
      className: String,
      ctorArg: Arg)(implicit ctorArgTag: ClassTag[Arg]): T = {
    try {
      val clazz = Utils.classForName(className)
      val ctor = clazz.getDeclaredConstructor(ctorArgTag.runtimeClass)
      ctor.newInstance(ctorArg).asInstanceOf[T]
    } catch {
      case NonFatal(e) =>
        throw new IllegalArgumentException(s"Error while instantiating '$className':", e)
    }
  }

  /**
   * Return true if Hive classes can be loaded, otherwise false.
   */
  private[spark] def hiveClassesArePresent: Boolean = {
    try {
      Utils.classForName(HIVE_SESSION_STATE_CLASS_NAME)
      Utils.classForName(HIVE_SHARED_STATE_CLASS_NAME)
      Utils.classForName("org.apache.hadoop.hive.conf.HiveConf")
      true
    } catch {
      case _: ClassNotFoundException | _: NoClassDefFoundError => false
    }
  }

  /**
   * Create a new [[SparkSession]] with a catalog backed by Hive.
   */
  def withHiveSupport(sc: SparkContext): SparkSession = {
    if (hiveClassesArePresent) {
      sc.conf.set(CATALOG_IMPLEMENTATION.key, "hive")
      new SparkSession(sc)
    } else {
      throw new IllegalArgumentException(
        "Unable to instantiate SparkSession with Hive support because Hive classes are not found.")
    }
  }

}

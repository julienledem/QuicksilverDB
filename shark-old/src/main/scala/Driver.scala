package shark

import shark.operators._
import spark._
import spark.SparkContext._

import org.apache.hadoop.conf.Configuration
import org.apache.hadoop.hive.cli.CliSessionState
import org.apache.hadoop.hive.common.JavaUtils
import org.apache.hadoop.hive.conf.HiveConf
import org.apache.hadoop.hive.ql.Context
import org.apache.hadoop.hive.ql.Driver
import org.apache.hadoop.hive.ql.exec.JobCloseFeedBack
import org.apache.hadoop.hive.ql.exec.Utilities
import org.apache.hadoop.hive.ql.exec.TableScanOperator
import org.apache.hadoop.hive.ql.metadata.Table
import org.apache.hadoop.hive.ql.processors.CommandProcessorResponse
import org.apache.hadoop.hive.ql.exec.Operator
import org.apache.hadoop.hive.ql.exec.OperatorFactory
import org.apache.hadoop.hive.ql.exec.OperatorFactory.OpTuple
import org.apache.hadoop.hive.ql.exec.MoveTask
import org.apache.hadoop.hive.ql.hooks.ExecuteWithHookContext
import org.apache.hadoop.hive.ql.hooks.Hook
import org.apache.hadoop.hive.ql.hooks.HookContext
import org.apache.hadoop.hive.ql.hooks.PostExecute
import org.apache.hadoop.hive.ql.hooks.PreExecute
import org.apache.hadoop.hive.ql.hooks.ReadEntity
import org.apache.hadoop.hive.ql.hooks.WriteEntity
import org.apache.hadoop.hive.ql.lockmgr.LockException
import org.apache.hadoop.hive.ql.lockmgr.HiveLock
import org.apache.hadoop.hive.ql.metadata.Partition;
import org.apache.hadoop.hive.ql.optimizer.ppr.PartitionPruner;
import org.apache.hadoop.hive.ql.parse.ASTNode
import org.apache.hadoop.hive.ql.parse.BaseSemanticAnalyzer
import org.apache.hadoop.hive.ql.parse.ParseContext
import org.apache.hadoop.hive.ql.parse.ParseDriver
import org.apache.hadoop.hive.ql.parse.ParseException
import org.apache.hadoop.hive.ql.parse.ParseUtils
import org.apache.hadoop.hive.ql.parse.PrunedPartitionList;
import org.apache.hadoop.hive.ql.parse.VariableSubstitution
import org.apache.hadoop.hive.ql.parse.SemanticAnalyzer
import org.apache.hadoop.hive.ql.parse.SemanticAnalyzerFactory
import org.apache.hadoop.hive.ql.parse.SemanticException
import org.apache.hadoop.hive.ql.plan.ExtractDesc
import org.apache.hadoop.hive.ql.plan.FilterDesc
import org.apache.hadoop.hive.ql.plan.FileSinkDesc
import org.apache.hadoop.hive.ql.plan.JoinDesc
import org.apache.hadoop.hive.ql.plan.LateralViewForwardDesc
import org.apache.hadoop.hive.ql.plan.LateralViewJoinDesc
import org.apache.hadoop.hive.ql.plan.LimitDesc
import org.apache.hadoop.hive.ql.plan.TableScanDesc
import org.apache.hadoop.hive.ql.plan.UDTFDesc
import org.apache.hadoop.hive.ql.plan.PlanUtils.ExpressionTypes
import org.apache.hadoop.hive.ql.plan.ReduceSinkDesc
import org.apache.hadoop.hive.ql.plan.SelectDesc
import org.apache.hadoop.hive.ql.plan.GroupByDesc
import org.apache.hadoop.hive.ql.plan.HiveOperation
import org.apache.hadoop.hive.ql.QueryPlan
import org.apache.hadoop.hive.ql.session.SessionState
import org.apache.hadoop.hive.serde2.objectinspector.ObjectInspector;
import org.apache.hadoop.hive.serde2.objectinspector.ObjectInspectorFactory;
import org.apache.hadoop.hive.serde2.objectinspector.ObjectInspectorUtils
import org.apache.hadoop.hive.serde2.objectinspector.ObjectInspectorUtils.ObjectInspectorCopyOption
import org.apache.hadoop.hive.serde2.objectinspector.primitive.PrimitiveObjectInspectorFactory;
import org.apache.hadoop.hive.serde2.objectinspector.StructObjectInspector
import org.apache.hadoop.hive.shims.ShimLoader
import org.apache.hadoop.util.ReflectionUtils

import java.io.File
import java.util.ArrayList
import java.util.Arrays

import scala.collection.JavaConversions._
import scala.collection.mutable.Stack

class SharkDriver(conf: HiveConf) extends Driver(conf) {

  var context: Context = null

  var pc: ParseContext = null

  var sem: SharkSemanticAnalyzer = null

  var sc: SparkContext = null

  var plan: QueryPlan = null

  /**
   * Replace the list of Hive operators defined in OperatorFactory.opvec with
   * Shark operators. OperatorFactory.opvec is a static array list.
   */
  def updateOperatorFactory(): Unit = {
    OperatorFactory.opvec.clear()
    /**
     * Missing Operators:
     * Union, SMBJoin, MapJoin, Script, Collect, Forward 
     */

    OperatorFactory.opvec.add(new OpTuple[LateralViewForwardDesc](
      classOf[LateralViewForwardDesc], classOf[RDDLateralViewForwardOperator]));
    OperatorFactory.opvec.add(new OpTuple[LateralViewJoinDesc](
      classOf[LateralViewJoinDesc], classOf[RDDLateralViewJoinOperator]));
    OperatorFactory.opvec.add(new OpTuple[UDTFDesc](
      classOf[UDTFDesc], classOf[RDDUDTFOperator]));
    OperatorFactory.opvec.add(new OpTuple[TableScanDesc](
      classOf[TableScanDesc], classOf[RDDTableScanOperator]));
    OperatorFactory.opvec.add(new OpTuple[SelectDesc](
      classOf[SelectDesc], classOf[RDDSelectOperator]));
    OperatorFactory.opvec.add(new OpTuple[FilterDesc](
      classOf[FilterDesc], classOf[RDDFilterOperator]));
    OperatorFactory.opvec.add(new OpTuple[FileSinkDesc](
      classOf[FileSinkDesc], classOf[RDDFileSinkOperator]));
    OperatorFactory.opvec.add(new OpTuple[ReduceSinkDesc](
      classOf[ReduceSinkDesc], classOf[RDDReduceSinkOperator]));
    OperatorFactory.opvec.add(new OpTuple[JoinDesc](
      classOf[JoinDesc], classOf[RDDJoinOperator]));
    OperatorFactory.opvec.add(new OpTuple[GroupByDesc](
      classOf[GroupByDesc], classOf[RDDGroupByOperator]));
    OperatorFactory.opvec.add(new OpTuple[LimitDesc](
      classOf[LimitDesc], classOf[RDDLimitOperator]));
    OperatorFactory.opvec.add(new OpTuple[ExtractDesc](
      classOf[ExtractDesc], classOf[RDDExtractOperator]));
  }

  override def init(): Unit = {
    // Setup Hive.
    updateOperatorFactory()
    super.init()
  }
  
  // @sameerag
  override def run(command: String) : CommandProcessorResponse = {
    var ret : CommandProcessorResponse = null;
    if (HiveConf.getBoolVar(conf, HiveConf.ConfVars.QUICKSILVER_SAMPLING_ENABLED) && 
       (command.toLowerCase().contains("create table") || command.toLowerCase().contains("load data")))
    {
        var numSamples = HiveConf.getIntVar(conf, HiveConf.ConfVars.SAMPLES_PER_TABLE);
        for (i <- 0 until numSamples)
      	  ret = run(command, i); 
    }
    else
    {
        ret = run(command, -1);
    }
    
    return ret;

  }
  
  override def run(command: String, executionFlag: Int): CommandProcessorResponse = {
    var errorMessage = null;
    var sqlState = null;
    
    super.compile(command, executionFlag)
    var ret = compile(command, executionFlag);
    if (ret != 0) {
      releaseLocks(context.getHiveLocks());
      return new CommandProcessorResponse(ret, errorMessage, sqlState);
    }
    
    //    ret = acquireReadWriteLocks();
    if (ret != 0) {
      releaseLocks(context.getHiveLocks());
      return new CommandProcessorResponse(ret, errorMessage, sqlState);
    }

    ret = execute();
    if (ret != 0) {
      releaseLocks(context.getHiveLocks());
      return new CommandProcessorResponse(ret, errorMessage, sqlState);
    }

    releaseLocks(context.getHiveLocks());
    return new CommandProcessorResponse(ret);
  }

  override def compile(cmd: String): Int = {
    return compile(cmd, -1);
  }

  // @sameerag
  override def compile(cmd: String, executionFlag: Int): Int = {
    try {
      var command = new VariableSubstitution().substitute(conf, cmd)
      context = new Context(conf)
      context.executionFlag = executionFlag;
      
      var pd: ParseDriver = new ParseDriver()
      var tree: ASTNode = pd.parse(command, context)
      tree = ParseUtils.findRootNonNullToken(tree)
      
      val base_sem: BaseSemanticAnalyzer = SemanticAnalyzerFactory.get(conf, tree)
      sem = base_sem match {
        case x: SemanticAnalyzer =>  new SharkSemanticAnalyzer(conf)
        case _ => null
      }
      //if null, we don't have a query
      if (sem != null) {
        // Do semantic analysis and plan generation
        sem.analyze(tree, context);
        // validate the plan
        sem.validate()
        if (SessionState.get().getCommandType() != HiveOperation.QUERY.getOperationName)
          return super.compile(cmd, executionFlag)
        plan = new QueryPlan(command, sem)
        if (sem.getFetchTask != null)
          sem.getFetchTask.initialize(conf, null, null)
      }
      else {
        return super.compile(cmd, executionFlag)
      }
      return super.compile(cmd, executionFlag)
    }
    catch {
      case e: SemanticException => {
        val errorMessage = "FAILED: Error in semantic analysis: " + Utilities.getNameMessage(e)
        println(errorMessage + "\n" + org.apache.hadoop.util.StringUtils.stringifyException(e))
        return 10
      }
      case e: ParseException => {
        val errorMessage = "FAILED: Parse Error: " + Utilities.getNameMessage(e)
        println(errorMessage + "\n" + org.apache.hadoop.util.StringUtils.stringifyException(e))
        return 11
      }
      case e: Exception => {
        val errorMessage = "FAILED: Hive Internal Error: " + Utilities.getNameMessage(e)
        println(errorMessage + "\n" + org.apache.hadoop.util.StringUtils.stringifyException(e))
        return 12
      }
    }
    0
  }

  override def execute(): Int = {
    var pctx: ParseContext = null
    if (sem != null)
      pctx = sem.getParseContext()
    else
      return super.execute()

    val topOps = pctx.getTopOps().values()
    val topToTable = pctx.getTopToTable()
    if (SessionState.get().getCommandType() != HiveOperation.QUERY.getOperationName) 
      return super.execute()
    

    conf.setVar(HiveConf.ConfVars.HIVEQUERYID, plan.getQueryId);
    conf.setVar(HiveConf.ConfVars.HIVEQUERYSTRING, plan.getQueryStr);

    runHooks(getPreExecHooks)
    var t: Table = pctx.getTopToTable().values().toArray()(0) match {
      case x: Table => x
      case _ => null
    }
    
    val topOp = topOps.toArray()(0).asInstanceOf[RDDTableScanOperator]

    RDDOperator.hconf = conf

    // Add table metadata to TableScanOperators
    topOps.foreach { op => {
      op.asInstanceOf[TableScanOperator].setTableDesc(Utilities.getTableDesc(topToTable.get(op)))
      op.asInstanceOf[RDDTableScanOperator].table = topToTable.get(op)
    }}

    // Serialize operator tree before it is initialized
    var time = System.currentTimeMillis
    RDDOperator.serializeOperatorTree(topOp)
    println("Op Tree serialization: " +  (System.currentTimeMillis - time))
    
    // Initialize top operators
    topOps.foreach { op => {
      op.initialize(conf, Array(ObjectInspectorUtils.getStandardObjectInspector(
        topToTable.get(op).getDeserializer().getObjectInspector())))
    }}

/*    // Get list of relevant partitions
    val partsList:PrunedPartitionList = 
      if (topToTable.get(topOps.toArray()(0)).isPartitioned()) 
        PartitionPruner.prune(
          pctx.getTopToTable().get(topOp), 
          pctx.getOpToPartPruner().get(topOp), 
          pctx.getConf(), "",
          pctx.getPrunedPartitions())
      else null
    val numParts = 
      if (partsList != null) 
        partsList.getConfirmedPartns().size
      else 0 

    // Initialize top operators
    var rowObjectInspector:ObjectInspector = null
    topOps.foreach({
      op => {
        val table = topToTable.get(op);
        println(Utilities.getTableDesc(table))
        rowObjectInspector = 
          if (numParts > 0) {
            val part = partsList.getConfirmedPartns().toArray()(0).asInstanceOf[Partition]
            val partDesc = Utilities.getPartitionDesc(part)
            val partProps = partDesc.getProperties()
            val tableDeser = partDesc.getDeserializerClass().newInstance()
            tableDeser.initialize(conf, partProps)
            val partCols = partProps.getProperty(
              org.apache.hadoop.hive.metastore.api.Constants.META_TABLE_PARTITION_COLUMNS);
            val partNames = new ArrayList[String]
            val partObjectInspectors = new ArrayList[ObjectInspector]
            partCols.trim().split("/").foreach(key => {
                partNames.add(key)
                partObjectInspectors.add(PrimitiveObjectInspectorFactory.javaStringObjectInspector)
            })
            val partObjectInspector = ObjectInspectorFactory
                .getStandardStructObjectInspector(partNames, partObjectInspectors);
            val oiList = Arrays.asList(
              tableDeser.getObjectInspector().asInstanceOf[StructObjectInspector], 
              partObjectInspector.asInstanceOf[StructObjectInspector])
            // new inspector is union of table + partition object inspectors
            ObjectInspectorUtils.getStandardObjectInspector(
                ObjectInspectorFactory.getUnionStructObjectInspector(oiList))
          } else 
              topToTable.get(op).getDeserializer().getObjectInspector()
          op.asInstanceOf[TableScanOperator].setTableDesc(
            Utilities.getTableDesc(topToTable.get(op)))
          op.initialize(conf, 
              Array(ObjectInspectorUtils.getStandardObjectInspector(rowObjectInspector)))
      }
    })

    // Process top operators
    topOps.foreach(
      op => {
        val table = topToTable.get(op)
        val table_desc = Utilities.getTableDesc(table)
        val table_path = table.getDataLocation.toString

        op.process(
      
          OperatorTreeCache.get(op) match {
            case Some(r) => { 
              println("Found table in cache")
              r
            }
            case None => {
              println("Didn't find table in cache")
              val r = 
              if (numParts > 0) {
                var rowsRDD: RDD[Object] = null
                RDDOperator.setInputOI(rowObjectInspector)
                val partitions = partsList.getConfirmedPartns().toArray()
                partitions.foreach(part => {
                  val partition = part.asInstanceOf[Partition]
                  val partDesc = Utilities.getPartitionDesc(partition)
                  val partRDD = (topToTable.get(op).getInputFormatClass() 
                    match {
                      case _ =>  sc.textFile(partition.getDataLocation().getPath()) 
                     })
                    .map(value => {
                      // Map each tuple to a row object
                       val deserializer = partDesc.getDeserializer()
                       val oi = deserializer.getObjectInspector().asInstanceOf[StructObjectInspector]

                       // Get table field ois
                       val ois = new ArrayList[ObjectInspector]
                       val tableFields= oi.asInstanceOf[StructObjectInspector].getAllStructFieldRefs()
                       tableFields.foreach(sf => ois.add(sf.getFieldObjectInspector()))

                       // Get partition field info
                       val partSpec = partDesc.getPartSpec()
                       val partProps = partDesc.getProperties()
                       val deser = partDesc.getDeserializer()
                       val rawRowObjectInspector = deser.getObjectInspector()
                       val partCols = partProps.getProperty(org.apache.hadoop.hive.metastore.api.Constants.META_TABLE_PARTITION_COLUMNS);
                       val partKeys = partCols.trim().split("/")
                       val partValues = new ArrayList[String]
                       partKeys.foreach(key => {
                         if (partSpec == null) {
                           partValues.add(new String)
                         } else {
                           partValues.add(new String(partSpec.get(key)))
                         }
                       })
                       // Obj is an ArrayList of col values (table + partition)
                       val obj = ObjectInspectorUtils.copyToStandardObject(deserializer.deserialize(value), oi)
                       obj.asInstanceOf[ArrayList[Any]].addAll(partValues.asInstanceOf[ArrayList[String]])
                       obj
                     })
                    rowsRDD = rowsRDD match  {
                      case null => partRDD
                      case _ => rowsRDD.union(partRDD)
                    }
                })
                rowsRDD
              } else {
                // Table is not partitioned
                val rdd = (topToTable.get(op).getInputFormatClass() 
                  match {
                    // Only support textfile for now
                    case _ => sc.textFile(table_path)
                  })
                  .mapPartitions((iter) => {
                    val deserializer = table_desc.getDeserializer()
                    val oi = deserializer.getObjectInspector.asInstanceOf[StructObjectInspector]
                    iter.map(value => ObjectInspectorUtils
                      .copyToStandardObject(deserializer.deserialize(value),oi))
                  })
                  rdd
              }       
              //r.cache()
              //OperatorTreeCache.put(op, r)
              r
            }
          }, 0)
      })
>>>>>>> master*/

    // Find FileSinkOperator
    def findSink(op: Operator[_ <: Serializable]) = {
      val s = Stack[Operator[_]]()
      s.push(op)
      while(s.head.getChildOperators != null && 
            !s.head.getChildOperators.isEmpty) {
        s.head.getChildOperators.foreach(s.push(_))
      }
      s.head
    }
    val sinkOp = findSink(topOp.asInstanceOf[Operator[_ <: Serializable]])

    sinkOp match {
      case op: RDDFileSinkOperator => 
        op.evaluate().foreach { _ => Unit } // Force spark evaluation
      case _ =>
        throw new Exception("File Sink Operator not found")
    }

    val feedBack = new JobCloseFeedBack
    val success = true
    sinkOp.jobClose(conf,success,feedBack)

    //Use MoveTasks to move data from temporary storage               
    sem.moveTasks.foreach { task => {
      task.initialize(conf,null,null)
      task.execute(null)
    }}

    runHooks(getPostExecHooks)
    0
  }

  override def getResults(res: java.util.ArrayList[String]): Boolean = {
    if (sem == null) {
      return super.getResults(res)
    }
    val ft = sem.getFetchTask
    if (ft != null) {
      ft.setMaxRows(getMaxRows)
      return ft.fetch(res)
    }
    else {
      return false
    }
  }

  def releaseLocks(){
    if (context != null && context.getHiveLockMgr() != null) {
      try {
        context.getHiveLockMgr.close();
        context.setHiveLocks(null);
      } catch {
        case e: LockException => Unit
      }
    }
  }

  def releaseLocks(hiveLocks: java.util.List[HiveLock]) {
    if (hiveLocks != null)
      context.getHiveLockMgr.releaseLocks(hiveLocks)
    context.setHiveLocks(null)
  }

  private def runHooks(hooks: java.util.List[Hook]) = {
    hooks.foreach { hook => hook match {
      case hook: ExecuteWithHookContext =>
        hook.run(new HookContext(plan, conf))
      case hook: PreExecute => {
        hook.run(SessionState.get, sem.getInputs, sem.getOutputs,
          ShimLoader.getHadoopShims.getUGIForConf(conf))
      }
      case hook: PostExecute => {
        val lineageInfo = if (SessionState.get != null)
          SessionState.get.getLineageState.getLineageInfo else null
        hook.run(SessionState.get, sem.getInputs, sem.getOutputs, lineageInfo,
          ShimLoader.getHadoopShims.getUGIForConf(conf))
      }
    }}
  }

  private def getHooks(hookStr: String): java.util.List[Hook] = {
    val peHooks = new ArrayList[Hook]()
    var peStr = hookStr.trim()
    if (peStr == "")
      return peHooks
    val peClasses = peStr.split(",")
    peClasses.foreach { peClass => {
        peHooks.add(Class.forName(peClass.trim(), true, JavaUtils.getClassLoader)
                    .newInstance().asInstanceOf[Hook])
    }}
    peHooks
  }

  private def getPreExecHooks(): java.util.List[Hook] = {
    var peStr = conf.getVar(HiveConf.ConfVars.PREEXECHOOKS)
    try {
      getHooks(peStr)
    }
    catch {
      case e: ClassNotFoundException => {
        println("Pre Exec Hook Class not found: " + e.getMessage)
        throw e
      }
    }
  }

  private def getPostExecHooks(): java.util.List[Hook] = {
    var peStr = conf.getVar(HiveConf.ConfVars.POSTEXECHOOKS)
    try {
      getHooks(peStr)
    }
    catch {
      case e: ClassNotFoundException => {
        println("Pre Exec Hook Class not found: " + e.getMessage)
        throw e
      }
    }
  }
}


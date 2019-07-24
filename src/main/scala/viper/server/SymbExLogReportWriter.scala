package viper.server

import logger.SymbLog
import logger.records.{RecordData, SymbolicRecord}
import logger.records.scoping.{CloseScopeRecord, OpenScopeRecord}
import logger.records.structural.{BranchInfo, BranchingRecord, JoiningRecord}
import viper.silicon._
import spray.json._
import viper.silicon.common.collections.immutable.InsertionOrderedSet
import viper.silicon.interfaces.state.Chunk
import viper.silicon.resources.{FieldID, PredicateID}
import viper.silicon.rules.InverseFunctions
import viper.silicon.state.terms.Term
import viper.silicon.state.{utils => _, _}
import viper.silver.ast.AbstractLocalVar


/** Wrapper for the SymbExLogReport conversion to JSON. */
object SymbExLogReportWriter {

  private def inverseFunctionsToJSON(invs: InverseFunctions): JsValue = {
    JsArray(
      TermWriter.toJSON(invs.axiomInversesOfInvertibles),
      TermWriter.toJSON(invs.axiomInvertiblesOfInverses)
    )
  }

  private def heapChunkToJSON(chunk: Chunk) = chunk match {
    case BasicChunk(PredicateID, id, args, snap, perm) =>
      JsObject(
        "type" -> JsString("basic_predicate_chunk"),
        "predicate" -> JsString(id.toString),
        "args" -> JsArray(args.map(TermWriter.toJSON).toVector),
        "snap" -> TermWriter.toJSON(snap),
        "perm" -> TermWriter.toJSON(perm)
      )

    case BasicChunk(FieldID, id, Seq(receiver), snap, perm) =>
      JsObject(
        "type" -> JsString("basic_field_chunk"),
        "field" -> JsString(id.toString),
        "receiver" -> TermWriter.toJSON(receiver),
        "snap" -> TermWriter.toJSON(snap),
        "perm" -> TermWriter.toJSON(perm)
      )

    // TODO: Are ID and bindings needed?
    case MagicWandChunk(id, bindings, args, snap, perm) =>
      JsObject(
        "type" -> JsString("basic_magic_wand_chunk"),
        "args" -> JsArray(args.map(TermWriter.toJSON).toVector),
        "snap" -> TermWriter.toJSON(snap),
        "perm" -> TermWriter.toJSON(perm)
      )

    case QuantifiedFieldChunk(id, fvf, perm, invs, cond, receiver, hints) =>
      JsObject(
        "type" -> JsString("quantified_field_chunk"),
        "field" -> JsString(id.toString),
        "field_value_function" -> TermWriter.toJSON(fvf),
        "perm" -> TermWriter.toJSON(perm),
        "invs" -> invs.map(inverseFunctionsToJSON).getOrElse(JsNull),
        "cond" -> cond.map(TermWriter.toJSON).getOrElse(JsNull),
        "receiver" -> receiver.map(TermWriter.toJSON).getOrElse(JsNull),
        "hints" -> (if (hints != Nil) JsArray(hints.map(TermWriter.toJSON).toVector) else JsNull)
      )

    case QuantifiedPredicateChunk(id, vars, psf, perm, invs, cond, singletonArgs, hints) =>
      JsObject(
        "type" -> JsString("quantified_predicate_chunk"),
        "vars" -> JsArray(vars.map(TermWriter.toJSON).toVector),
        "predicate" -> JsString(id.toString),
        "predicate_snap_function" -> TermWriter.toJSON(psf),
        "perm" -> TermWriter.toJSON(perm),
        "invs" -> invs.map(inverseFunctionsToJSON).getOrElse(JsNull),
        "cond" -> cond.map(TermWriter.toJSON).getOrElse(JsNull),
        "singleton_args" -> singletonArgs.map(as => JsArray(as.map(TermWriter.toJSON).toVector)).getOrElse(JsNull),
        "hints" -> (if (hints != Nil) JsArray(hints.map(TermWriter.toJSON).toVector) else JsNull)
      )

    case QuantifiedMagicWandChunk(id, vars, wsf, perm, invs, cond, singletonArgs, hints) =>
      JsObject(
        "type" -> JsString("quantified_magic_wand_chunk"),
        "vars" -> JsArray(vars.map(TermWriter.toJSON).toVector),
        "predicate" -> JsString(id.toString),
        "wand_snap_function" -> TermWriter.toJSON(wsf),
        "perm" -> TermWriter.toJSON(perm),
        "invs" -> invs.map(inverseFunctionsToJSON).getOrElse(JsNull),
        "cond" -> cond.map(TermWriter.toJSON).getOrElse(JsNull),
        "singleton_args" -> singletonArgs.map(as => JsArray(as.map(TermWriter.toJSON).toVector)).getOrElse(JsNull),
        "hints" -> (if (hints != Nil) JsArray(hints.map(TermWriter.toJSON).toVector) else JsNull)
      )

    case other => JsObject(
      "type" -> JsString("unstructrured_chunk"),
      "value" -> JsString(other.toString)
    )
  }

  /** Translates all members to a JsArray.
    *
    * @param members A symbolic log per member to translate.
    * @return array of all records.
    */
  def toJSON(members: List[SymbLog]): JsArray = {
    val records = members.foldLeft(Vector[JsValue]()) {
      (prevVal: Vector[JsValue], member: SymbLog) => prevVal ++ toJSON(member)
    }
    JsArray(records)
  }

  /** Translates a SymbLog to a vector of JsValues.
    *
    * @param symbLog The symbolic log to translate.
    * @return array of all records.
    */
  def toJSON(symbLog: SymbLog): Vector[JsValue] = {
    symbLog.log.map(toJSON).toVector
  }

  /** Translates a SymbolicRecord to a JsValue.
    *
    * @param record The symbolic to translate.
    * @return The record translated as a JsValue.
    */
  def toJSON(record: SymbolicRecord): JsValue = {

    var isJoinPoint: Boolean = false
    var isScopeOpen: Boolean = false
    var isScopeClose: Boolean = false
    var isSyntactic: Boolean = false
    var branches: Option[JsArray] = None
    var data: Option[JsObject] = toJSON(record.getData())
    record match {
      case br: BranchingRecord => branches = Some(JsArray(br.getBranchInfos().map(toJSON).toVector))
      case jr: JoiningRecord => isJoinPoint = true
      case os: OpenScopeRecord => isScopeOpen = true
      case cs: CloseScopeRecord => isScopeClose = true
      case _ =>
    }

    var fields: Map[String, JsValue] = new Map()

    fields = fields + ("id" -> JsNumber(record.id))
    fields = fields + ("kind" -> JsString(record.toTypeString()))
    fields = fields + ("value" -> JsString(record.toSimpleString()))
    if (isJoinPoint) {
      fields = fields + ("isJoinPoint" -> JsTrue)
    }
    if (isScopeOpen) {
      fields = fields + ("isScopeOpen" -> JsTrue)
    }
    if (isScopeClose) {
      fields = fields + ("isScopeClose" -> JsTrue)
    }
    if (isSyntactic) {
      fields = fields + ("isSyntactic" -> JsTrue)
    }
    branches match {
      case Some(jsBranches) => fields = fields + ("branches" -> jsBranches)
      case _ =>
    }
    data match {
      case Some(jsData) => fields = fields + ("data" -> jsData)
      case _ =>
    }

    JsObject(fields)
  }

  def toJSON(data: RecordData): Option[JsObject] = {
    var fields: Map[String, JsValue] = new Map()

    data.refId match {
      case Some(refId) => fields = fields + ("refId" -> JsNumber(refId))
      case _ =>
    }

    if (data.isSmtQuery) {
      fields = fields + ("isSmtQuery" -> JsTrue)
    }

    data.timeMs match {
      case Some(timeMs) => fields = fields + ("timeMs" -> JsNumber(timeMs))
      case _ =>
    }

    data.pos match {
      case Some(pos) => fields = fields + ("pos" -> JsString(pos))
      case _ =>
    }

    data.lastSMTQuery match {
      case Some(smtQuery) => fields = fields + ("lastSMTQuery" -> TermWriter.toJSON(smtQuery))
      case _ =>
    }

    data.store match {
      case Some(store) => fields = fields + ("store" -> toJSON(store))
      case _ =>
    }

    data.heap match {
      case Some(heap) => fields = fields + ("heap" -> toJSON(heap))
      case _ =>
    }

    data.oldHeap match {
      case Some(oldHeap) => fields = fields + ("oldHeap" -> toJSON(oldHeap))
      case _ =>
    }

    data.pcs match {
      case Some(pcs) => fields = fields + ("pcs" -> toJSON(pcs))
      case _ =>
    }

    if (fields.isEmpty) None else Some(JsObject(fields))
  }

  def toJSON(store: Store): JsArray = {
    JsArray(store.values.map({
      case (v @ AbstractLocalVar(name), value) =>
        JsObject(
          "name" -> JsString(name),
          "value" -> TermWriter.toJSON(value),
          "sort" -> TermWriter.toJSON(value.sort)
        )
      case other =>
        JsString(s"Unexpected variable in store '$other'")
    }).toVector)
  }

  def toJSON(heap: Heap): JsArray = {
    JsArray(heap.values.map(heapChunkToJSON).toVector)
  }

  def toJSON(pcs: InsertionOrderedSet[Term]): JsArray = {
    JsArray(pcs.map(TermWriter.toJSON).toVector)
  }

  def toJSON(info: BranchInfo): JsObject = {
    val records: List[JsNumber] = info.records.map(record => JsNumber(record.id))

    JsObject(
      "isReachable" -> JsBoolean(info.isReachable),
      "startTimeMs" -> JsNumber(info.startTimeMs),
      "records" -> JsArray(records.toVector)
    )
  }
}

/* NSC -- new Scala compiler
 * Copyright 2005-2011 LAMP/EPFL
 * @author  Martin Odersky
 */

package scala.tools.nsc
package ast

import symtab._
import reporters.Reporter
import util.{Position, NoPosition}
import util.DocStrings._
import scala.reflect.internal.Chars._
import scala.collection.mutable

/*
 *  @author  Martin Odersky
 *  @version 1.0
 */
trait DocComments { self: Global =>

  def reporter: Reporter

  /** The raw doc comment map */
  val docComments = mutable.HashMap[Symbol, DocComment]()

  /** Associate comment with symbol `sym` at position `pos`. */
  def docComment(sym: Symbol, docStr: String, pos: Position = NoPosition) =
    if ((sym ne null) && (sym ne NoSymbol))
      docComments += (sym -> DocComment(docStr, pos))

  /** The raw doc comment of symbol `sym`, as it appears in the source text, "" if missing.
   */
  def rawDocComment(sym: Symbol): String =
    docComments get sym map (_.raw) getOrElse ""

  /** The position of the raw doc comment of symbol `sym`, or NoPosition if missing
   *  If a symbol does not have a doc comment but some overridden version of it does,
   *  the position of the doc comment of the overridden version is returned instead.
   */
  def docCommentPos(sym: Symbol): Position =
    getDocComment(sym) map (_.pos) getOrElse NoPosition
    
  /** A version which doesn't consider self types, as a temporary measure:
   *  an infinite loop has broken out between superComment and cookedDocComment
   *  since r23926.
   */
  private def allInheritedOverriddenSymbols(sym: Symbol): List[Symbol] = {
    if (!sym.owner.isClass) Nil
    else sym.owner.ancestors map (sym overriddenSymbol _) filter (_ != NoSymbol)
  }

  /** The raw doc comment of symbol `sym`, minus @usecase and @define sections, augmented by
   *  missing sections of an inherited doc comment.
   *  If a symbol does not have a doc comment but some overridden version of it does,
   *  the doc comment of the overridden version is copied instead.
   */
  def cookedDocComment(sym: Symbol, docStr: String = ""): String = {
    val ownComment = if (docStr.length == 0) docComments get sym map (_.template) getOrElse ""
                     else DocComment(docStr).template
    superComment(sym) match {
      case None =>
        ownComment
      case Some(sc) =>
        if (ownComment == "") sc
        else merge(sc, ownComment, sym)
    }
  }

  /** The cooked doc comment of symbol `sym` after variable expansion, or "" if missing.
   *
   *  @param sym  The symbol for which doc comment is returned
   *  @param site The class for which doc comments are generated
   *  @throws ExpansionLimitExceeded  when more than 10 successive expansions
   *                                  of the same string are done, which is
   *                                  interpreted as a recursive variable definition.
   */
  def expandedDocComment(sym: Symbol, site: Symbol, docStr: String = ""): String = {
    // when parsing a top level class or module, use the (module-)class itself to look up variable definitions
    val site1 = if ((sym.isModule || sym.isClass) && site.hasPackageFlag) sym
                else site
    expandVariables(cookedDocComment(sym, docStr), sym, site1)
  }

  /** The cooked doc comment of symbol `sym` after variable expansion, or "" if missing.
   *  @param sym  The symbol for which doc comment is returned (site is always the containing class)
   */
  def expandedDocComment(sym: Symbol): String = expandedDocComment(sym, sym.enclClass)

  /** The list of use cases of doc comment of symbol `sym` seen as a member of class
   *  `site`. Each use case consists of a synthetic symbol (which is entered nowhere else),
   *  of an expanded doc comment string, and of its position.
   *
   *  @param sym  The symbol for which use cases are returned
   *  @param site The class for which doc comments are generated
   *  @throws ExpansionLimitExceeded  when more than 10 successive expansions
   *                                  of the same string are done, which is
   *                                  interpreted as a recursive variable definition.
   */
  def useCases(sym: Symbol, site: Symbol): List[(Symbol, String, Position)] = {
    def getUseCases(dc: DocComment) = {
      for (uc <- dc.useCases; defn <- uc.expandedDefs(site)) yield
        (defn,
         expandVariables(merge(cookedDocComment(sym), uc.comment.raw, defn, copyFirstPara = true), sym, site),
         uc.pos)
    }
    getDocComment(sym) map getUseCases getOrElse List()
  }

  def useCases(sym: Symbol): List[(Symbol, String, Position)] = useCases(sym, sym.enclClass)

  /** Returns the javadoc format of doc comment string `s`, including wiki expansion
   */
  def toJavaDoc(s: String): String = expandWiki(s)

  private val wikiReplacements = List(
    ("""(\n\s*\*?)(\s*\n)"""    .r, """$1 <p>$2"""),
    ("""<([^\w/])"""            .r, """&lt;$1"""),
    ("""([^\w/])>"""            .r, """$1&gt;"""),
    ("""\{\{\{(.*(?:\n.*)*)\}\}\}""".r, """<pre>$1</pre>"""),
    ("""`([^`]*)`"""            .r, """<code>$1</code>"""),
    ("""__([^_]*)__"""          .r, """<u>$1</u>"""),
    ("""''([^']*)''"""          .r, """<i>$1</i>"""),
    ("""'''([^']*)'''"""        .r, """<b>$1</b>"""),
    ("""\^([^^]*)\^"""          .r, """<sup>$1</sup>"""),
    (""",,([^,]*),,"""          .r, """<sub>$1</sub>"""))

  /** Returns just the wiki expansion (this would correspond to
   *  a comment in the input format of the JavaDoc tool, modulo differences
   *  in tags.)
   */
  def expandWiki(str: String): String =
    (str /: wikiReplacements) { (str1, regexRepl) => regexRepl._1 replaceAllIn(str1, regexRepl._2) }

  private def getDocComment(sym: Symbol): Option[DocComment] =
    mapFind(sym :: allInheritedOverriddenSymbols(sym))(docComments get _)

  /** The cooked doc comment of an overridden symbol */
  protected def superComment(sym: Symbol): Option[String] =
    allInheritedOverriddenSymbols(sym).iterator map (x => cookedDocComment(x)) find (_ != "")

  private def mapFind[A, B](xs: Iterable[A])(f: A => Option[B]): Option[B] =
    xs collectFirst scala.Function.unlift(f)

  private def isMovable(str: String, sec: (Int, Int)): Boolean =
    startsWithTag(str, sec, "@param") ||
    startsWithTag(str, sec, "@tparam") ||
    startsWithTag(str, sec, "@return")

  /** Merge elements of doccomment `src` into doc comment `dst` for symbol `sym`.
   *  In detail:
   *  1. If `copyFirstPara` is true, copy first paragraph
   *  2. For all parameters of `sym` if there is no @param section
   *     in `dst` for that parameter name, but there is one on `src`, copy that section.
   *  3. If there is no @return section in `dst` but there is one in `src`, copy it.
   */
  def merge(src: String, dst: String, sym: Symbol, copyFirstPara: Boolean = false): String = {
    val srcSections = tagIndex(src)
    val dstSections = tagIndex(dst)
    val srcParams   = paramDocs(src, "@param", srcSections)
    val dstParams   = paramDocs(dst, "@param", dstSections)
    val srcTParams  = paramDocs(src, "@tparam", srcSections)
    val dstTParams  = paramDocs(dst, "@tparam", dstSections)
    val out         = new StringBuilder
    var copied      = 0
    var tocopy      = startTag(dst, dstSections dropWhile (!isMovable(dst, _)))

    if (copyFirstPara) {
      val eop = // end of comment body (first para), which is delimited by blank line, or tag, or end of comment
        (findNext(src, 0)(src.charAt(_) == '\n')) min startTag(src, srcSections)
      out append src.substring(0, eop).trim
      copied = 3
      tocopy = 3
    }

    def mergeSection(srcSec: Option[(Int, Int)], dstSec: Option[(Int, Int)]) = dstSec match {
      case Some((start, end)) =>
        if (end > tocopy) tocopy = end
      case None =>
        srcSec match {
          case Some((start1, end1)) => {
            out append dst.substring(copied, tocopy).trim
            out append "\n"
            copied = tocopy
            out append src.substring(start1, end1).trim
          }
          case None =>
        }
    }

    for (params <- sym.paramss; param <- params)
      mergeSection(srcParams get param.name.toString, dstParams get param.name.toString)
    for (tparam <- sym.typeParams)
      mergeSection(srcTParams get tparam.name.toString, dstTParams get tparam.name.toString)
    mergeSection(returnDoc(src, srcSections), returnDoc(dst, dstSections))

    if (out.length == 0) dst
    else {
      out append dst.substring(copied)
      out.toString
    }
  }

  /** Maps symbols to the variable -> replacement maps that are defined
   *  in their doc comments
   */
  private val defs = mutable.HashMap[Symbol, Map[String, String]]() withDefaultValue Map()

  /** Lookup definition of variable.
   *
   *  @param vble  The variable for which a definition is searched
   *  @param owner The current owner in which variable definitions are searched.
   *  @param site  The class for which doc comments are generated
   */
  def lookupVariable(vble: String, site: Symbol): Option[String] = site match {
    case NoSymbol => None
    case _        =>
      val searchList =
        if (site.isModule) site :: site.info.baseClasses
        else site.info.baseClasses

      searchList collectFirst { case x if defs(x) contains vble => defs(x)(vble) } match {
        case Some(str) if str startsWith '$'  => lookupVariable(str.tail, site)
        case res                              => res orElse lookupVariable(vble, site.owner)
      }
  }

  /** Expand variable occurrences in string `str`, until a fix point is reached or
   *  a expandLimit is exceeded.
   *
   *  @param str   The string to be expanded
   *  @param sym   The symbol for which doc comments are generated
   *  @param site  The class for which doc comments are generated
   *  @return      Expanded string
   */
  protected def expandVariables(initialStr: String, sym: Symbol, site: Symbol): String = {
    val expandLimit = 10

    def expandInternal(str: String, depth: Int): String = {
      if (depth >= expandLimit)
        throw new ExpansionLimitExceeded(str)

      val out         = new StringBuilder
      var copied, idx = 0
      // excluding variables written as \$foo so we can use them when
      // necessary to document things like Symbol#decode
      def isEscaped = idx > 0 && str.charAt(idx - 1) == '\\'
      while (idx < str.length) {
        if ((str charAt idx) != '$' || isEscaped)
          idx += 1
        else {
          val vstart = idx
          idx = skipVariable(str, idx + 1)
          def replaceWith(repl: String) {
            out append str.substring(copied, vstart)
            out append repl
            copied = idx
          }
          variableName(str.substring(vstart + 1, idx)) match {
            case "super"    =>
              superComment(sym) foreach { sc =>
                val superSections = tagIndex(sc)
                replaceWith(sc.substring(3, startTag(sc, superSections)))
                for (sec @ (start, end) <- superSections)
                  if (!isMovable(sc, sec)) out append sc.substring(start, end)
              }
            case "" => idx += 1
            case vname  =>
              lookupVariable(vname, site) match {
                case Some(replacement) => replaceWith(replacement)
                case None              => reporter.warning(sym.pos, "Variable " + vname + " undefined in comment for " + sym)
              }            
            }
        }
      }
      if (out.length == 0) str
      else {
        out append str.substring(copied)
        expandInternal(out.toString, depth + 1)
      }
    }
    
    // We suppressed expanding \$ throughout the recursion, and now we
    // need to replace \$ with $ so it looks as intended.
    expandInternal(initialStr, 0).replaceAllLiterally("""\$""", "$")
  }

  // !!! todo: inherit from Comment?
  case class DocComment(raw: String, pos: Position = NoPosition) {

    /** Returns:
     *   template: the doc comment minus all @define and @usecase sections
     *   defines : all define sections (as strings)
     *   useCases: all usecase sections (as instances of class UseCase)
     */
    lazy val (template, defines, useCases) = {
      val sections = tagIndex(raw)

      val defines = sections filter { startsWithTag(raw, _, "@define") }
      val usecases = sections filter { startsWithTag(raw, _, "@usecase") }

      val end = startTag(raw, (defines ::: usecases).sortBy(_._1))

      (if (end == raw.length - 2) raw else raw.substring(0, end) + "*/",
       defines map { case (start, end) => raw.substring(start, end) },
       usecases map { case (start, end) => decomposeUseCase(start, end) })
    }

    private def decomposeUseCase(start: Int, end: Int): UseCase = {
      val codeStart    = skipWhitespace(raw, start + "@usecase".length)
      val codeEnd      = skipToEol(raw, codeStart)
      val code         = raw.substring(codeStart, codeEnd)
      val codePos      = subPos(codeStart, codeEnd)
      val commentStart = skipLineLead(raw, codeEnd + 1) min end
      val comment      = "/** " + raw.substring(commentStart, end) + "*/"
      val commentPos   = subPos(commentStart, end)
      
      UseCase(DocComment(comment, commentPos), code, codePos)
    }

    private def subPos(start: Int, end: Int) =
      if (pos == NoPosition) NoPosition
      else {
        val start1 = pos.start + start
        val end1 = pos.end + end
        pos withStart start1 withPoint start1 withEnd end1
      }

    def defineVariables(sym: Symbol) = {
      val Trim = "(?s)^[\\s&&[^\n\r]]*(.*?)\\s*$".r

      defs(sym) ++= defines.map {
        str => {
          val start = skipWhitespace(str, "@define".length)
          val (key, value) = str.splitAt(skipVariable(str, start))
          key.drop(start) -> value
        }
      } map {
        case (key, Trim(value)) =>
          variableName(key) -> value.replaceAll("\\s+\\*+$", "")
      }
    }
  }

  case class UseCase(comment: DocComment, body: String, pos: Position) {
    var defined: List[Symbol] = List() // initialized by Typer
    var aliases: List[Symbol] = List() // initialized by Typer

    def expandedDefs(site: Symbol): List[Symbol] = {

      def select(site: Type, name: Name, orElse: => Type): Type = {
        val member = site.nonPrivateMember(name)
        if (member.isTerm) singleType(site, member)
        else if (member.isType) site.memberType(member)
        else orElse
      }

      def getSite(name: Name): Type = {
        def findIn(sites: List[Symbol]): Type = sites match {
          case List() => NoType
          case site :: sites1 => select(site.thisType, name, findIn(sites1))
        }
        val (classes, pkgs) = site.ownerChain.span(!_.isPackageClass)
        findIn(classes ::: List(pkgs.head, definitions.RootClass))
      }

      def getType(str: String): Type = {
        def getParts(start: Int): List[String] = {
          val end = skipIdent(str, start)
          if (end == start) List()
          else str.substring (start, end) :: {
            if (end < str.length && (str charAt end) == '.') getParts(end + 1)
            else List()
          }
        }
        val parts = getParts(0)
        assert(parts.nonEmpty, "parts is empty '" + str + "' in site " + site)
        val partnames = (parts.init map newTermName) :+ newTypeName(parts.last)
        val (start, rest) = parts match {
          case "this" :: _      => (site.thisType, partnames.tail)
          case _ :: "this" :: _ => 
            site.ownerChain.find(_.name == partnames.head) match {
              case Some(clazz)  => (clazz.thisType, partnames drop 2)
              case _            => (NoType, Nil)
            }
          case _ =>
            (getSite(partnames.head), partnames.tail)
        }
        (start /: rest)(select(_, _, NoType))
      }

      val aliasExpansions: List[Type] =
        for (alias <- aliases) yield
          lookupVariable(alias.name.toString.substring(1), site) match {
            case Some(repl) =>
              val tpe = getType(repl.trim)
              if (tpe != NoType) tpe
              else {
                val alias1 = alias.cloneSymbol(definitions.RootClass)
                alias1.name = repl.toTypeName
                typeRef(NoPrefix, alias1, Nil)
              }
            case None =>
              typeRef(NoPrefix, alias, Nil)
          }

      def subst(sym: Symbol, from: List[Symbol], to: List[Type]): Type =
        if (from.isEmpty) sym.tpe
        else if (from.head == sym) to.head
        else subst(sym, from.tail, to.tail)

      val substAliases = new TypeMap {
        def apply(tp: Type) = mapOver(tp) match {
          case tp1 @ TypeRef(pre, sym, args) if (sym.name.length > 1 && sym.name(0) == '$') =>
            subst(sym, aliases, aliasExpansions) match {
              case TypeRef(pre1, sym1, _) =>
                typeRef(pre1, sym1, args)
              case _ =>
                tp1
            }
          case tp1 =>
            tp1
        }
      }

      for (defn <- defined) yield {
        defn.cloneSymbol.setFlag(Flags.SYNTHETIC).setInfo(
          substAliases(defn.info).asSeenFrom(site.thisType, defn.owner))
      }
    }
  }

  class ExpansionLimitExceeded(str: String) extends Exception
}

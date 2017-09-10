package utest
package framework
//import acyclic.file

import scala.collection.mutable
import scala.util.{Failure, Success}

object Formatter extends Formatter
/**
 * Default implementation of [[Formatter]], also used by the default SBT test
 * framework. Allows some degree of customization of the formatted test results.
 */
trait Formatter {

  def formatColor: Boolean = true
  def formatTruncateHeight: Int = 100
  def formatTrace: Boolean = true
  def formatWrapThreshold: Int = 90

  def formatValue(x: Any) = formatValueColor(x.toString)

  def formatValueColor: fansi.Attrs =
    if (formatColor) fansi.Color.Blue
    else fansi.Attrs.Empty

  def formatException(x: Throwable) = {
    val output = mutable.Buffer.empty[fansi.Str]
    var current = x
    while(current != null){
      output.append(formatResultColor(false)(current.toString))
      val stack = current.getStackTrace
      val assertDropped = stack.indexWhere(x =>
        x.getClassName == "utest.asserts.Asserts$" && x.getMethodName == "wrap"
      )

      val frameworkDropped = stack.indexWhere(x =>
        x.getClassName == "utest.framework.TestThunkTree" && x.getMethodName == "run"
      )


      stack
        .slice(
          assertDropped match {case -1 => 0 case n => n + 2},
          frameworkDropped match {case -1 => stack.length case n => n}
        )
        .foreach { e =>
          output.append(
            "\n",
            fansi.Color.Red(e.getClassName + "."),
            fansi.Color.Yellow(e.getMethodName), "(",
            fansi.Color.Green(e.getFileName), ":",
            fansi.Color.Green(e.getLineNumber.toString),
            ")"
          )
        }
      if (current != null) output.append("\n")
      current = current.getCause
    }

    fansi.Str.join(output:_*)
  }

  def formatResultColor(success: Boolean): fansi.Attrs =
    if (!formatColor) fansi.Attrs.Empty
    else if (success) fansi.Color.Green
    else fansi.Color.Red

  def formatMillisColor: fansi.Attrs =
    if (formatColor) fansi.Color.DarkGray
    else fansi.Attrs.Empty

  private[this] def prettyTruncate(r: Result, leftIndent: String): fansi.Str = {
    val rendered: fansi.Str = r.value match{
      case Success(()) => ""
      case Success(v) => formatValue(v)
      case Failure(e) => formatException(e)
    }

    val truncUnit = {
      val output = mutable.Buffer.empty[fansi.Str]
      val plainText = rendered.plainText
      var index = 0
      while(index < plainText.length && output.length < formatTruncateHeight){
        val nextWholeLine = index + (formatWrapThreshold - leftIndent.length)
        val (nextIndex, skipOne) = plainText.indexOf('\n', index + 1) match{
          case -1 =>
            if (nextWholeLine < plainText.length) (nextWholeLine, false)
            else (plainText.length, false)
          case n =>
            if (nextWholeLine < n) (nextWholeLine, false)
            else (n, true)
        }

        output.append(rendered.substring(index, nextIndex))
        if (skipOne) index = nextIndex + 1
        else index = nextIndex
      }
      if (index < plainText.length){
        output.append("...")
      }
      output.flatMap(Seq[fansi.Str]("\n", leftIndent, _)).drop(2)
    }

    fansi.Str.join(truncUnit:_*)
  }

  def wrapLabel(leftIndentCount: Int, r: Result, label: String): fansi.Str = {
    val leftIndent = "  " * leftIndentCount
    val lhs = fansi.Str.join(
      leftIndent,
      formatIcon(r.value.isInstanceOf[Success[_]]), " ",
      label, " ",
      formatMillisColor(r.milliDuration + "ms"), " "
    )

    val rhs = prettyTruncate(r, leftIndent + "  ")


    val sep =
      if (lhs.length + rhs.length <= formatWrapThreshold) " "
      else "\n" + leftIndent + "  "

    lhs ++ sep ++ rhs
  }

  def formatSingle(path: Seq[String], r: Result): Option[fansi.Str] = Some{
    wrapLabel(0, r, path.mkString("."))
  }

  def formatIcon(success: Boolean): fansi.Str = {
    formatResultColor(success)(if (success) "+" else "X")
  }

  def format(topLevelName: String, results: HTree[String, Result]): Option[fansi.Str] = Some{
    val linearized = mutable.Buffer.empty[fansi.Str]

    val relabelled = results match{
      case HTree.Node(v, c@_*) => HTree.Node(topLevelName, c:_*)
      case HTree.Leaf(r) => HTree.Leaf(r.copy(name = topLevelName))
    }
    rec(0, relabelled){
      case (depth, Left(name)) => linearized.append("  " * depth + "- " + name)
      case (depth, Right(r)) => linearized.append(wrapLabel(depth, r, r.name))
    }

    linearized.mkString("\n")
  }

  private[this] def rec(depth: Int, r: HTree[String, Result])
                       (f: (Int, Either[String, Result]) => Unit): Unit = {
    r match{
      case HTree.Leaf(l) => f(depth, Right(l))
      case HTree.Node(v, c@_*) =>
        f(depth, Left(v))
        c.foreach(rec(depth+1, _)(f))
    }
  }
}


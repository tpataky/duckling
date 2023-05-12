package io.github.tpataky.duckling

import cats.data.{Chain, NonEmptyChain}
import cats.syntax.apply._
import cats.syntax.flatMap._
import cats.syntax.foldable._
import cats.syntax.functor._
import cats.{Eval, Id, Monad, Monoid}

import java.util
import scala.annotation.tailrec

case class LayoutOpts(pageWidth: Int, indent: Int)

trait RendererM[M[_], -A, R] {
  def init: M[R]
  def indent(n: Int, r0: R): M[R]
  def enterAnnotatedSection(a: A, r0: R): M[R]
  def leaveAnnotatedSection(a: A, r0: R): M[R]
  def lineBreak(r0: R): M[R]
  def str(s: String, r0: R): M[R]
}

trait Renderer[-A, R] extends RendererM[Id, A, R] {
  def init: R
  def indent(n: Int, r0: R): R
  def enterAnnotatedSection(a: A, r0: R): R
  def leaveAnnotatedSection(a: A, r0: R): R
  def lineBreak(r0: R): R
  def str(s: String, r0: R): R
}

class MonoidRenderer[R](inject: String => R, lineSeparator: R)(implicit M: Monoid[R]) extends Renderer[Any, R] {

  override def init: R = M.empty

  override def indent(n: Int, r0: R): R = M.combine(r0, inject(" " * n))

  override def enterAnnotatedSection(a: Any, r0: R): R = r0

  override def leaveAnnotatedSection(a: Any, r0: R): R = r0

  override def lineBreak(r0: R): R = M.combine(r0, lineSeparator)

  override def str(s: String, r0: R): R = M.combine(r0, inject(s))
}

object Renderer {

  def apply[R: Monoid](inject: String => R, lineSeparator: R): MonoidRenderer[R] =
    new MonoidRenderer[R](inject, lineSeparator)

  def string(lineSeparator: String): MonoidRenderer[String] = apply(identity, lineSeparator)
}

sealed trait Layout[+A] {
  def flush: Layout[A]

  def concat[B >: A](other: Layout[B]): Layout[B]

  def indent1(spaces: Int): Layout[A] = {
    def go(l: Layout[A]): Layout[A] = {
      l match {
        case Layout.Indent(_) | Layout.Str(_) => l
        case Layout.LineBreak()               => l.concat(Layout.Indent[A](spaces))
        case Layout.Annotated(a, l) =>
          val l1 = go(l)
          Layout.Annotated(a, l1)
        case Layout.Concatenated(ls) =>
          val ls1 = ls.map(go)
          Layout.Concatenated(ls1)
      }
    }

    go(this)
  }

  def indent(spaces: Int): Layout[A] = Layout.Indent(spaces).concat(indent1(spaces))

  def render[B >: A, R](runner: Renderer[B, R]): R =
    render(new RendererM[Eval, A, R] {
      override def init: Eval[R] = Eval.now(runner.init)

      override def indent(n: Int, r0: R): Eval[R] = Eval.now(runner.indent(n, r0))

      override def enterAnnotatedSection(a: A, r0: R): Eval[R] = Eval.now(runner.enterAnnotatedSection(a, r0))

      override def leaveAnnotatedSection(a: A, r0: R): Eval[R] = Eval.now(runner.leaveAnnotatedSection(a, r0))

      override def lineBreak(r0: R): Eval[R] = Eval.now(runner.lineBreak(r0))

      override def str(s: String, r0: R): Eval[R] = Eval.now(runner.str(s, r0))
    }).value

  def render[M[_]: Monad, B >: A, R](runner: RendererM[M, B, R]): M[R] = {
    def go(l: Layout[A], r: R): M[R] =
      l match {
        case Layout.Indent(n)        => runner.indent(n, r)
        case Layout.Str(s)           => runner.str(s, r)
        case Layout.LineBreak()      => runner.lineBreak(r)
        case Layout.Concatenated(ls) => ls.foldLeftM(r)((rn, l) => go(l, rn))
        case Layout.Annotated(a, l) =>
          for {
            r1 <- runner.enterAnnotatedSection(a, r)
            r2 <- go(l, r1)
            r3 <- runner.leaveAnnotatedSection(a, r2)
          } yield r3
      }

    runner.init.flatMap(r => go(this, r))
  }
}

object Layout {
  case class Indent[A](n: Int) extends Layout[A] {
    override def flush: Layout[A] = concat(LineBreak())
    override def concat[B >: A](other: Layout[B]): Layout[B] = Concatenated(Chain(this, other))
  }
  case class Str[A](s: String) extends Layout[A] {
    override def flush: Layout[A] = concat(LineBreak())
    override def concat[B >: A](other: Layout[B]): Layout[B] = Concatenated(Chain(this, other))
  }
  case class LineBreak[A]() extends Layout[A] {
    override def flush: Layout[A] = concat(this)
    override def concat[B >: A](other: Layout[B]): Layout[B] = Concatenated(Chain(this, other))
  }
  case class Annotated[A](a: A, underlying: Layout[A]) extends Layout[A] {
    override def flush: Layout[A] = concat(LineBreak())
    override def concat[B >: A](other: Layout[B]): Layout[B] = Concatenated(Chain(this, other))
  }
  case class Concatenated[A](ls: Chain[Layout[A]]) extends Layout[A] {
    override def flush: Layout[A] = Concatenated(ls :+ LineBreak())
    override def concat[B >: A](other: Layout[B]): Layout[B] =
      other match {
        case Concatenated(ts) => Concatenated(ls ++ ts)
        case _                => Concatenated(ls :+ other)
      }
  }
}

trait Measure {
  def badness: Int
  def maxWidth: Int
  def lastWidth: Int
  def height: Int
  def cost: Int
}

final case class MeasuredLayout[A](
    badness: Int,
    maxWidth: Int,
    lastWidth: Int,
    height: Int,
    cost: Int,
    layout: Eval[Layout[A]]
) extends Measure {

  def compare(other: MeasuredLayout[A]): Int =
    if (badness == other.badness) {
      if (height == other.height) {
        if (cost == other.cost) {
          if (lastWidth == other.lastWidth) {
            maxWidth - other.maxWidth
          } else lastWidth - other.lastWidth
        } else cost - other.cost
      } else height - other.height
    } else badness - other.badness

  def dominates(other: Measure): Boolean =
    badness <= other.badness && height <= other.height && cost <= other.cost

  def flush: MeasuredLayout[A] =
    copy(lastWidth = 0, height = height + 1, layout = layout.map(_.flush))

  def concat(other: MeasuredLayout[A], pageWidth: Int): MeasuredLayout[A] =
    MeasuredLayout(
      badness = Math.max(0, lastWidth + other.maxWidth - pageWidth),
      maxWidth = Math.max(maxWidth, lastWidth + other.maxWidth),
      lastWidth = lastWidth + other.lastWidth,
      height = height + other.height,
      cost = cost + other.cost,
      (layout, other.layout).mapN((a, b) => a.concat(b.indent1(lastWidth))).memoize
    )
  def annotate(a: A): MeasuredLayout[A] = copy(layout = layout.map(Layout.Annotated(a, _)))

  def indent(n: Int, pageWidth: Int): MeasuredLayout[A] =
    MeasuredLayout(
      badness = Math.max(0, maxWidth + n - pageWidth),
      maxWidth = maxWidth + n,
      lastWidth = lastWidth + n,
      height = height,
      cost = cost,
      layout.map(_.indent(n)).memoize
    )
}
object MeasuredLayout {
  def apply[A](s: String, pageWidth: Int): MeasuredLayout[A] =
    MeasuredLayout(
      badness = Math.max(0, s.length - pageWidth),
      maxWidth = s.length,
      lastWidth = s.length,
      height = 0,
      cost = 0,
      Eval.now(Layout.Str[A](s))
    )
}

sealed trait Doc[+A] {
  final def concat[B >: A](other: Doc[B]): Doc[B] = Doc.Concat(this, other)

  final def +[B >: A](other: Doc[B]): Doc[B] = concat(other)

  final def vconcat[B >: A](other: Doc[B]): Doc[B] = Doc.Concat(this.flush, other)

  final def \[B >: A](other: Doc[B]): Doc[B] = vconcat(other)

  final def orElse[B >: A](alternative: Doc[B]): Doc[B] = Doc.Alternatives(this, alternative)

  final def |[B >: A](alternative: Doc[B]): Doc[B] = orElse(alternative)

  final def flush: Doc[A] = Doc.Flush(this)

  final def annotate[B >: A](a: B): Doc[B] = Doc.Annotate(a, this)

  final def hangWith[B >: A](sep: Doc[B], n: Int, d: Doc[B]): Doc[B] =
    hangWith(sep, Some(n), d)

  final def hangWith[B >: A](sep: Doc[B], d: Doc[B]): Doc[B] =
    hangWith(sep, None, d)

  private[this] def hangWith[B >: A](sep: Doc[B], n: Option[Int], d: Doc[B]): Doc[B] =
    (this + sep + d) | (this.flush + Doc.Indent(n, d))

  final def hang[B >: A](n: Int, d: Doc[B]): Doc[B] =
    hangWith(Doc.space, Some(n), d)

  final def hang[B >: A](d: Doc[B]): Doc[B] =
    hangWith(Doc.space, None, d)

  final def <\>[B >: A](other: Doc[B]): Doc[B] =
    hangWith(Doc.space, None, other)

  final def <\\>[B >: A](other: Doc[B]): Doc[B] =
    hangWith(Doc.empty, None, other)

  final def <+>[B >: A](other: Doc[B]): Doc[B] =
    this + Doc.space + other

  final def enclose[B >: A](l: Doc[B], r: Doc[B]): Doc[B] =
    l + this + r

  final def parens: Doc[A] = enclose(Doc.lpar, Doc.rpar)

  final def quotes: Doc[A] = enclose(Doc.quote, Doc.quote)

  final def quotes2: Doc[A] = enclose(Doc.quote2, Doc.quote2)

  final def layout(opts: LayoutOpts): Layout[A] = Doc.layout(this, opts).head.layout.value

  final def render[R](opts: LayoutOpts, renderer: Renderer[A, R]): R = {
    val ls = layout(opts)
    ls.render(renderer)
  }

  final def render[M[_]: Monad, R](opts: LayoutOpts, renderer: RendererM[M, A, R]): M[R] = {
    val ls = layout(opts)
    ls.render(renderer)
  }

}

object Doc {

  implicit def monoid[A]: Monoid[Doc[A]] = new Monoid[Doc[A]] {
    override def empty: Doc[A] = Doc.empty

    override def combine(x: Doc[A], y: Doc[A]): Doc[A] = x + y
  }

  case class Text[A](value: String) extends Doc[A]
  case class Flush[A](d: Doc[A]) extends Doc[A]
  case class Concat[A](a: Doc[A], b: Doc[A]) extends Doc[A]
  case class Indent[A](spaces: Option[Int], d: Doc[A]) extends Doc[A]
  case class Alternatives[A](a: Doc[A], b: Doc[A]) extends Doc[A]
  case class Annotate[A](a: A, d: Doc[A]) extends Doc[A]
  case class Select[A](p: Measure => Boolean, d: Doc[A], orElse: Doc[A]) extends Doc[A]

  val empty: Doc[Nothing] = Text("")
  val space: Doc[Nothing] = Text(" ")
  val equals_ : Doc[Nothing] = Doc.char('=')
  val backSlash: Doc[Nothing] = Doc.char('\\')
  val dot: Doc[Nothing] = Doc.char('.')
  val comma: Doc[Nothing] = Doc.char(',')
  val colon: Doc[Nothing] = Doc.char(':')
  val semi: Doc[Nothing] = Doc.char(';')
  val quote: Doc[Nothing] = Doc("'")
  val quote2: Doc[Nothing] = Doc.char('"')
  val lpar: Doc[Nothing] = Doc.char('(')
  val rpar: Doc[Nothing] = Doc.char(')')
  val langle: Doc[Nothing] = Doc.char('<')
  val rangle: Doc[Nothing] = Doc.char('>')
  val lbrace: Doc[Nothing] = Doc.char('{')
  val rbrace: Doc[Nothing] = Doc.char('}')
  val bra: Doc[Nothing] = Doc.char('[')
  val ket: Doc[Nothing] = Doc.char(']')

  def apply(s: String): Doc[Nothing] = Text(s)

  def string[A](s: String): Doc[A] = Predef.augmentString(s).linesIterator.map(Doc(_)).reduce(_ \ _)

  def char(c: Char): Doc[Nothing] = Doc(c.toString)

  def sep[A](docs: Doc[A]*): Doc[A] = grouped(space)(docs.map((Some(0), _)))

  def grouped[A](sep: Doc[A])(docs: Seq[(Option[Int], Doc[A])]): Doc[A] = {
    if (docs.isEmpty) empty
    else {
      val horizontal = docs.view.map(_._2).reduce(_ + sep + _)
      val vertical = docs.view.map({ case (indent, d) => Doc.Indent(indent, d): Doc[A] }).reduce((a, b) => a \ b)
      Doc.Select(_.height == 0, horizontal, vertical)
    }
  }

  def intersperse[A](docs: List[Doc[A]], sep: Doc[A]): List[Doc[A]] =
    docs match {
      case Nil | _ :: Nil => docs
      case hd :: tl       => (hd + sep) :: intersperse(tl, sep)
    }

  def layout[A](d: Doc[A], opts: LayoutOpts): NonEmptyChain[MeasuredLayout[A]] = {

    def go(
        m: util.IdentityHashMap[Doc[A], Eval[NonEmptyChain[MeasuredLayout[A]]]],
        d: Doc[A]
    ): Eval[NonEmptyChain[MeasuredLayout[A]]] = {
      m.get(d) match {
        case null =>
          val computed: Eval[NonEmptyChain[MeasuredLayout[A]]] = Eval
            .defer({
              d match {
                case Doc.Text(value) => Eval.now(NonEmptyChain.one(MeasuredLayout[A](value, opts.pageWidth)))
                case Doc.Flush(d)    => go(m, d).map(_.map(_.flush))
                case Doc.Indent(n, d) =>
                  go(m, d).map(_.map(_.indent(n.getOrElse(opts.indent), opts.pageWidth)))
                case Doc.Concat(a, b) =>
                  val as = go(m, a)
                  val bs = go(m, b)
                  (as, bs).mapN((as, bs) => Internal.bests((as, bs).mapN((a, b) => a.concat(b, opts.pageWidth))))
                case Doc.Alternatives(a, b) =>
                  val as = go(m, a)
                  val bs = go(m, b)
                  (as, bs).mapN((as, bs) => Internal.bests(Internal.merge(as, bs)))
                case Doc.Select(pred, primary, alternative) =>
                  val as = go(m, primary)
                  val bs = go(m, alternative)
                  (as, bs).mapN({ (as, bs) =>
                    val remaining = NonEmptyChain.fromChain(as.filter(pred))
                    remaining match {
                      case Some(as) => Internal.bests(Internal.merge(as, bs))
                      case None     => bs
                    }
                  })
                case Doc.Annotate(a, d) =>
                  val ls = go(m, d)
                  ls.map(_.map(ml => ml.copy(layout = ml.layout.map(l => Layout.Annotated(a, l)))))
              }
            })
            .memoize
          m.put(d, computed)
          computed
        case computed => computed
      }
    }

    go(new util.IdentityHashMap(), d).value
  }

  object Internal {
    def merge[A](
        xs: NonEmptyChain[MeasuredLayout[A]],
        ys: NonEmptyChain[MeasuredLayout[A]]
    ): NonEmptyChain[MeasuredLayout[A]] = {
      val (xhd, xtl) = xs.uncons
      val (yhd, ytl) = ys.uncons

      @tailrec
      def loop(
          acc: Chain[MeasuredLayout[A]],
          xs: Chain[MeasuredLayout[A]],
          ys: Chain[MeasuredLayout[A]]
      ): Chain[MeasuredLayout[A]] =
        (xs.uncons, ys.uncons) match {
          case (None, _) => acc ++ ys
          case (_, None) => acc ++ xs
          case (Some((xhd, xtl)), Some((yhd, ytl))) =>
            if (xhd.compare(yhd) <= 0) loop(acc :+ xhd, xtl, ys)
            else loop(acc :+ yhd, xs, ytl)
        }

      if (xhd.compare(yhd) <= 0)
        NonEmptyChain.fromChainPrepend(xhd, loop(Chain.empty, xtl, ys.toChain))
      else NonEmptyChain.fromChainPrepend(yhd, loop(Chain.empty, xs.toChain, ytl))
    }

    def bests[A](ls: NonEmptyChain[MeasuredLayout[A]]): NonEmptyChain[MeasuredLayout[A]] =
      computePareto(discardInvalid(ls))

    def discardInvalid[A](ps: NonEmptyChain[MeasuredLayout[A]]): NonEmptyChain[MeasuredLayout[A]] =
      if (ps.length == 1) ps
      else
        NonEmptyChain
          .fromChain(ps.filter(_.badness == 0))
          .getOrElse(NonEmptyChain.one(ps.head))

    def computePareto[A](xs: NonEmptyChain[MeasuredLayout[A]]): NonEmptyChain[MeasuredLayout[A]] = {

      @tailrec
      def go(acc: NonEmptyChain[MeasuredLayout[A]], xs: Chain[MeasuredLayout[A]]): NonEmptyChain[MeasuredLayout[A]] = {
        xs.uncons match {
          case None           => acc
          case Some((hd, tl)) => if (acc.exists(a => a.dominates(hd))) go(acc, tl) else go(acc :+ hd, tl)
        }
      }

      go(NonEmptyChain.one(xs.head), xs.tail)
    }
  }
}

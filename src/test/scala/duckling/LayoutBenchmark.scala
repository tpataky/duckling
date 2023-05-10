package duckling

import cats.data.NonEmptyChain
import org.openjdk.jmh.annotations.{Benchmark, Scope, State}

@State(Scope.Benchmark)
class LayoutBenchmark {

  def arr =
    Doc.grouped(Doc.space)(
      Doc
        .intersperse[Nothing](List.tabulate(100)(n => Doc(n.toString)), Doc.comma)
        .map((None, _))
        .prepended((Some(0), Doc.bra))
        .appended((Some(0), Doc.ket))
    )

  val bigShared = {
    val sharedInner = arr
    Doc.grouped(Doc.space)(
      Doc
        .intersperse[Nothing](
          List.tabulate(100)(n => (Doc(n.toString * (n / 5)) + Doc.colon) <\> sharedInner),
          Doc.comma
        )
        .map((None, _))
        .prepended((Some(0), Doc.lbrace))
        .appended((Some(0), Doc.rbrace))
    )
  }

  val bigNoSharing = {
    Doc.grouped(Doc.space)(
      Doc
        .intersperse[Nothing](List.tabulate(100)(n => (Doc(n.toString * (n / 5)) + Doc.colon) <\> arr), Doc.comma)
        .map((None, _))
        .prepended((Some(0), Doc.lbrace))
        .appended((Some(0), Doc.rbrace))
    )
  }

  @Benchmark
  def layoutShared(): NonEmptyChain[MeasuredLayout[Nothing]] = {
    Doc.layout(bigShared, LayoutOpts(80, 2))
  }

  @Benchmark
  def layoutNotShared(): NonEmptyChain[MeasuredLayout[Nothing]] = {
    Doc.layout(bigNoSharing, LayoutOpts(80, 2))
  }

}

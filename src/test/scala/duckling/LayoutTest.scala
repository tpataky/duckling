package duckling

import munit.FunSuite

class LayoutTest extends FunSuite {

  val renderer = Renderer.string("\n")

  test("flush") {
    assertEquals(
      Doc("a").flush.render(LayoutOpts(80, 2), renderer),
      """a
        |""".stripMargin
    )
  }

  test("+") {
    assertEquals(
      (Doc("a") + Doc("b")).render(LayoutOpts(80, 2), renderer),
      "ab"
    )
    assertEquals(
      (Doc("a").flush + Doc("b")).render(LayoutOpts(80, 2), renderer),
      """a
        |b""".stripMargin
    )
    assertEquals(
      (Doc("a") + (Doc("b").flush + Doc("c"))).render(LayoutOpts(80, 2), renderer),
      """ab
        | c""".stripMargin
    )
  }

  test("|") {
    assertEquals(
      (Doc("a       b") | Doc("a") \ Doc("b")).render(LayoutOpts(5, 2), renderer),
      """a
        |b""".stripMargin
    )
    assertEquals(
      (Doc("a       b") | Doc("a") \ Doc("b")).render(LayoutOpts(80, 2), renderer),
      "a       b"
    )
  }

}

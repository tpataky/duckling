# duckling
Home of the duckling pretty printing library

The library is based on the ideas presented in Jean-Phillipe Bernandy's paper https://jyp.github.io/pdf/Prettiest.pdf,
but the actual implementation uses the techniques of the Racket implementation [pprint-compact](https://docs.racket-lang.org/pprint-compact/index.html).
Crucial difference between pprint-compact and duckling is that there is no `fail` document in duckling. duckling will
always produce a layout even if it's wider than the page width.

# Maven coordinates for sbt
```"io.github.tpataky" %% "duckling" % "0.0.1"```

# Howto
## The `Doc` type
The centerpoint of the library is the `Doc` type representing a document.
```scala
sealed trait Doc[+A]
```
The `Doc` type's type parameter is for user defined annotations. Annotations can be used to attach semantic
information to parts of a document (For example color or style. Or it can be used to classify document fragments as
keywords, punctuation or operator and choose styling based on this information).

To turn an abstract document into a concrete representation the `render` method can be used. 

```scala
def render[R](opts: LayoutOpts, renderer: Renderer[A, R]): R
```

Let's take a look at an example:
```scala
val d = Doc("Hello")
val layoutOptions = LayoutOpts(pageWidth = 120, indent = 2)
d.render(layoutOptions, Renderer.string) // val res0: String = Hello

```

## Creating documents

### From strings
In the example above we constructed a document straight from a string. A document created this way is morally
equivalent to the string from which it was created. Special characters in the string are not interpreted or escaped
in this stage, and whether such characters have any meaning how they affect the output is dependent on the renderer
and the actual result format. (For example the presence or absence of a line break does not meaningfully alter the
result of rendering a document to HTML unless the renderer replaces newlines with `</br>`).

Documents created this way will not be broken apart even if it leads to lines longer that the specified page width.

The `Doc` companion object has many predefined values for frequently used strings. For performance reasons use
these predefined instances instead of defining common documents over and over. 

### Joining with `+` and `concat`
`+` is the horizontal concatenation operator, and an alias to the `concat` method. Joins two documents in an
unbreakable way with no separator between them, meaning that documents created this way will not be broken up even
if it leads to lines longer than the current page width. 

The following should hold for all strings `a` and `b`: `Doc(a) + Doc(b) === Doc(a + b)`. For all types `A` the `Doc[A`]
type forms a monoid where `+` is the associative operator and `Doc.empty` is the neutral element.

```scala
val layoutOptions = LayoutOpts(pageWidth = 80, indent = 2)

val d = Doc("Hello") + Doc("world")

d.render(layoutOptions, Renderer.string)  // val res0: String = Helloworld
```

The precise behaviour is given in the aforementioned paper by Bernardy, but for a simple visual representation
here is a diagram:
```
 aaaaaaa     bbbbbbbbb       aaaaaaa
 aaaaa       bbbbbb          aaaaa
 aaaaaaa  +  bbbbbbb      =  aaaaaaa
 aaa                         aaabbbbbbbbb
                                bbbbbb
                                bbbbbbb
```

### Joining with `\ ` and `vconcat` 
`\ ` and the `vconcat` methods compose documents vertically. Vertical composition equals to adding an empty line
to the end of the first document (with the `flush` method) and then horizontally concatenating with the second one.
Example:
```scala
val layoutOptions = LayoutOpts(pageWidth = 80, indent = 2)

val d = Doc("Hello") \ Doc("world")

d.render(layoutOptions, Renderer.string) 
// val res0: String =
// Hello
// world
```

### Joining with `<+>`
`a <+> b`  is a shorthand for `a + Doc(" ") + b`. Joins two documents with a space between them.
Example:
```scala
val layoutOptions = LayoutOpts(pageWidth = 80, indent = 2)

val d = Doc("Hello") <+> Doc("world")

d.render(layoutOptions, Renderer.string)
// val res0: String = Hello world
```

### Joining with `hangWith`
The `hangWith` method will produce two alternative layouts for a document: one where the documents are joined
horizontally with the given separator between the two, the second one where the two are joined horizontally
with potentially some extra indentation added to the second document.
```scala
val d = Doc("lorem ipsum").hangWith(Doc.space, 3, Doc("dolor sit amet"))

d.render(LayoutOpts(15, 2), Renderer.string)
// val res0: String =
//   lorem ipsum
//     dolor sit amet

d.render(LayoutOpts(80, 2), Renderer.string)
// val res1: String = lorem ipsum dolor sit amet
```

### Annotating documents
It is possible to annotate documents with arbitrary values with the `annotate` method. The renderer can then use these
values to produce different output based on the annotations.

```scala
sealed trait Color
object Color {
  case object Red extends Color
  case object Green extends Color
  case object Blue extends Color
}

val renderer = new Renderer[Color, String] {
  override def init: String = ""

  override def indent(n: Int, r0: String): String = r0 + " " * n

  override def enterAnnotatedSection(a: Color, r0: String): String =
    a match {
      case Color.Red   => r0 + """<span style="color:red">"""
      case Color.Green => r0 + """<span style="color:green">"""
      case Color.Blue  => r0 + """<span style="color:blue">"""
    }

  override def leaveAnnotatedSection(a: Color, r0: String): String =
    r0 + "</span>"

  override def lineBreak(r0: String): String = r0 + "</br>\n"

  override def str(s: String, r0: String): String =
    r0 + s
}

val d = Doc("red").annotate(Color.Red) \
  Doc("green").annotate(Color.Green) \
  Doc("blue").annotate(Color.Blue)


d.render(LayoutOpts(80, 2), renderer)
// val res0: String =
//   <span style="color:red">red</span></br>
//   <span style="color:green">green</span></br>
//   <span style="color:blue">blue</span>
```
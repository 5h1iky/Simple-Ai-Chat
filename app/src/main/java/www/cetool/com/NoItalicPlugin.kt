package www.cetool.com

import io.noties.markwon.AbstractMarkwonPlugin
import io.noties.markwon.MarkwonVisitor
import org.commonmark.node.Emphasis

class NoItalicPlugin : AbstractMarkwonPlugin() {
    override fun configureVisitor(builder: MarkwonVisitor.Builder) {
        builder.on(Emphasis::class.java) { visitor, node ->
            visitor.visitChildren(node)
        }
    }
}

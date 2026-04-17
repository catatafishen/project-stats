package com.github.projectstats

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiJavaFile
import com.intellij.psi.PsiManager
import com.siyeh.ig.classmetrics.CyclomaticComplexityVisitor
import org.jetbrains.kotlin.psi.KtCatchClause
import org.jetbrains.kotlin.psi.KtDoWhileExpression
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtForExpression
import org.jetbrains.kotlin.psi.KtIfExpression
import org.jetbrains.kotlin.psi.KtTreeVisitorVoid
import org.jetbrains.kotlin.psi.KtWhenExpression
import org.jetbrains.kotlin.psi.KtWhileExpression

/**
 * Uses IntelliJ PSI for accurate cyclomatic complexity on Java and Kotlin files.
 * Returns null for unsupported file types, signalling the caller to fall back to keyword counting.
 */
object PsiComplexityCalculator {
    fun calculate(file: VirtualFile, project: Project): Int? {
        val psiFile = PsiManager.getInstance(project).findFile(file) ?: return null
        return when (psiFile) {
            is PsiJavaFile -> {
                val visitor = CyclomaticComplexityVisitor()
                psiFile.accept(visitor)
                visitor.complexity
            }
            is KtFile -> {
                val visitor = KotlinComplexityVisitor()
                psiFile.accept(visitor)
                visitor.complexity
            }
            else -> null
        }
    }
}

private class KotlinComplexityVisitor : KtTreeVisitorVoid() {
    var complexity = 0
        private set

    override fun visitIfExpression(expression: KtIfExpression) {
        complexity++
        expression.acceptChildren(this)
    }

    override fun visitForExpression(expression: KtForExpression) {
        complexity++
        expression.acceptChildren(this)
    }

    override fun visitWhileExpression(expression: KtWhileExpression) {
        complexity++
        expression.acceptChildren(this)
    }

    override fun visitDoWhileExpression(expression: KtDoWhileExpression) {
        complexity++
        expression.acceptChildren(this)
    }

    override fun visitWhenExpression(expression: KtWhenExpression) {
        // Each non-else arm is a separate decision path (mirrors Java switch-case CC counting)
        val branchCount = expression.entries.count { !it.isElse }.coerceAtLeast(1)
        complexity += branchCount
        expression.acceptChildren(this)
    }

    override fun visitCatchSection(catchClause: KtCatchClause) {
        complexity++
        catchClause.acceptChildren(this)
    }
}

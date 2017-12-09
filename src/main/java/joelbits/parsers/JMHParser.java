package joelbits.parsers;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.ImportDeclaration;
import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.body.CallableDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.type.Type;
import com.github.javaparser.ast.visitor.VoidVisitorAdapter;
import com.github.javaparser.utils.Log;
import com.google.auto.service.AutoService;
import joelbits.parsers.spi.Parser;
import joelbits.parsers.types.ParserType;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.*;

import static java.util.stream.Collectors.collectingAndThen;
import static java.util.stream.Collectors.toList;

/**
 * Use this class to load a Java file and parse it as desired. Method declarations are used as keys since
 * a method declaration is unique within a file in a revision.
 */
@AutoService(Parser.class)
public final class JMHParser implements Parser {
    private CompilationUnit compilationUnit;
    private String fileName;
    private final Map<String, List<String>> methodCalls = new HashMap<>();
    private final Map<String, MethodDeclaration> declarationMappings = new HashMap<>();

    /**
     *  Receives the path to a revision of a file and loads that file in the parser. Then parses the class into
     *  more fine grained parts. The data is removed each time a new file is parsed in order to make a parser reusable
     *  and reduce memory footprint.
     *
     * @param filePath    path to the current revision of the file to parse, e.g., c:\gitProjects\JCTools\JCTools\jctools-core/src/test/java/org/jctools/maps/nbhm_test/NBHM_Tester2.java
     */
    @Override
    public void parse(String filePath) {
        this.fileName = new File(filePath).getName();
        loadFile(filePath);
        clearData();

        new MethodVisitor().visit(compilationUnit, this);
        Log.info("Parsing of " + fileName + " completed");
    }

    private void loadFile(String filePath) {
        try (FileInputStream in = new FileInputStream(filePath)) {
            compilationUnit = JavaParser.parse(in);
            Log.info("Loaded " + fileName);
        } catch (IOException e) {
            Log.error(e.toString(), e);
        }
    }

    private void clearData() {
        methodCalls.clear();
        declarationMappings.clear();
    }

    /**
     * Use this method to check if the class has any JMH import statements.
     *
     * @return      true if any import statement containing jmh exists, otherwise false
     */
    public boolean hasJMHImport() {
        for (ImportDeclaration declaration : compilationUnit.getImports()) {
            if (declaration.getNameAsString().toUpperCase().contains(ParserType.JMH.name())) {
                return true;
            }
        }
        return false;
    }

    public List<String> allImports() {
        return compilationUnit.getImports().stream()
                .map(ImportDeclaration::getNameAsString)
                .collect(collectingAndThen(toList(), Collections::unmodifiableList));
    }

    public String fileName() {
        return fileName;
    }

    public List<String> allMethodNames() {
        return declarationMappings.values().stream()
                .map(MethodDeclaration::getNameAsString)
                .collect(collectingAndThen(toList(), Collections::unmodifiableList));
    }

    public Set<String> allMethodDeclarations() {
        return Collections.unmodifiableSet(declarationMappings.keySet());
    }

    public String methodBody(String methodDeclaration) {
        return Optional.ofNullable(declarationMappings.get(methodDeclaration))
                .map(MethodDeclaration::getBody)
                .filter(Optional::isPresent)
                .map(Optional::get)
                .map(BlockStmt::toString)
                .orElse("");
    }

    public List<String> methodAnnotations(String methodDeclaration) {
        List<AnnotationExpr> annotations = Optional.ofNullable(declarationMappings.get(methodDeclaration))
                .map(MethodDeclaration::getAnnotations)
                .orElse(new NodeList<>());
        return annotations.isEmpty() ? Collections.emptyList() : annotations.stream()
                .map(AnnotationExpr::getNameAsString)
                .collect(collectingAndThen(toList(), Collections::unmodifiableList));
    }

    public List<String> allMethodCallsWithinMethod(String methodDeclaration) {
        return methodCalls.computeIfPresent(methodDeclaration, (declaration, methodCalls) -> methodCalls);
    }

    public List<String> methodParameterTypes(String methodDeclaration) {
        List<Type> parameterTypes = Optional.ofNullable(declarationMappings.get(methodDeclaration))
                .map(MethodDeclaration::getSignature)
                .map(CallableDeclaration.Signature::getParameterTypes)
                .orElse(Collections.emptyList());
        return parameterTypes.isEmpty() ?  Collections.emptyList() : parameterTypes.stream()
                .map(Type::asString)
                .collect(collectingAndThen(toList(), Collections::unmodifiableList));
    }

    public String methodReturnType(String methodDeclaration) {
        return Optional.ofNullable(declarationMappings.get(methodDeclaration))
                .map(MethodDeclaration::getType)
                .map(Type::asString)
                .orElse("");
    }

    /**
     * A visitor parsing data from the loaded class on a method level.
     */
    class MethodVisitor extends VoidVisitorAdapter<JMHParser> {
        @Override
        public void visit(MethodDeclaration method, JMHParser parser) {
            String methodDeclaration = method.getDeclarationAsString();

            parser.declarationMappings.put(methodDeclaration, method);
            parser.methodCalls.put(methodDeclaration, new ArrayList<>());
            method.accept(new MethodCallVisitor(), parser.methodCalls.get(methodDeclaration));
        }
    }

    /**
     * A visitor parsing data about method invocations performed inside a specific method.
     */
    class MethodCallVisitor extends VoidVisitorAdapter<List<String>> {
        @Override
        public void visit(MethodCallExpr methodCall, List<String> methodInvocations) {
            super.visit(methodCall, methodInvocations);
            methodInvocations.add(methodCall.toString());
        }
    }

    @Override
    public String type() {
        return ParserType.JMH.name();
    }

    @Override
    public String toString() {
        return "JMHParser";
    }
}
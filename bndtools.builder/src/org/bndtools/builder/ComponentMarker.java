package org.bndtools.builder;

import java.io.File;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import org.bndtools.api.BndtoolsConstants;
import org.bndtools.api.ILogger;
import org.bndtools.api.Logger;
import org.bndtools.builder.decorator.ui.ComponentDecorator;
import org.bndtools.builder.decorator.ui.ComponentPackageDecorator;
import org.eclipse.core.resources.IMarker;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.jdt.core.IAnnotation;
import org.eclipse.jdt.core.IClasspathEntry;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IImportDeclaration;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.IMemberValuePair;
import org.eclipse.jdt.core.IPackageDeclaration;
import org.eclipse.jdt.core.IPackageFragment;
import org.eclipse.jdt.core.IPackageFragmentRoot;
import org.eclipse.jdt.core.ISourceRange;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.core.JavaModelException;
import org.eclipse.jdt.core.SourceRange;
import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTParser;
import org.eclipse.jdt.core.dom.ASTVisitor;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.MarkerAnnotation;
import org.eclipse.jdt.core.dom.MemberValuePair;
import org.eclipse.jdt.core.dom.NormalAnnotation;
import org.eclipse.jface.text.BadLocationException;
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.IDecoratorManager;
import org.eclipse.ui.PlatformUI;

import aQute.bnd.build.Project;

/**
 * This class creates markers for classes that contain the {@link org.osgi.service.component.annotations.Component}
 * annotation, and stores this information in the {@link BuilderPlugin} for use by {@link ComponentDecorator}and
 * {@link ComponentPackageDecorator}.
 *
 * @author wodencafe
 */
public class ComponentMarker {

    private static final ILogger logger = Logger.getLogger(ComponentMarker.class);
    public static final String ANNOTATION_COMPONENT_PACKAGE = "org.osgi.service.component.annotations";
    public static final String ANNOTATION_COMPONENT_FQN = ANNOTATION_COMPONENT_PACKAGE + ".Component";

    private final static ConcurrentMap<String,ComponentDecorationToken> compMap = new ConcurrentHashMap<>();

    public final static ConcurrentMap<String,ComponentDecorationToken> getComponentDecorationMap() {
        return compMap;
    }

    public static void updateComponentMarkers(IProject project, Project model) throws Exception {
        try {
            if (!project.isOpen()) {
                return;
            }
            IJavaProject javaProject = JavaCore.create(project);
            if (javaProject == null) {
                return; // project is not a java project
            }
            if (!project.getProject().hasNature(BndtoolsConstants.NATURE_ID)) {
                return; // project is not a bndtools project
            }

            clearComponentMarkers(project);

            for (IClasspathEntry cpe : javaProject.getRawClasspath()) {
                if (cpe.getEntryKind() != IClasspathEntry.CPE_SOURCE) {
                    continue;
                }
                for (IPackageFragmentRoot pkgRoot : javaProject.findPackageFragmentRoots(cpe)) {
                    assert pkgRoot.getKind() == IPackageFragmentRoot.K_SOURCE;
                    IResource pkgRootResource = pkgRoot.getCorrespondingResource();
                    if (pkgRootResource == null) {
                        continue;
                    }
                    File pkgRootFile = pkgRootResource.getLocation().toFile();
                    boolean pkgInSourcePath = model.getSourcePath().contains(pkgRootFile);
                    if (pkgInSourcePath) {
                        for (IJavaElement child : pkgRoot.getChildren()) {
                            IPackageFragment pkg = (IPackageFragment) child;
                            assert pkg.getKind() == IPackageFragmentRoot.K_SOURCE;

                            if (pkg.containsJavaResources()) {
                                parseChildrenForComponents(pkg);
                            }
                        }
                    }
                }

            }
            updateComponentDecorators();

        } catch (CoreException e) {
            logger.logError("Component Marker error", e);
        }
    }

    public static void updateComponentDecorators() {
        Display.getDefault().asyncExec(new Runnable() {
            @Override
            public void run() {
                IDecoratorManager idm = PlatformUI.getWorkbench().getDecoratorManager();
                idm.update("bndtools.componentDecorator");
                idm.update("bndtools.componentPackageDecorator");
            }
        });
    }

    public static void clearComponentMarkers(IProject project) {
        ConcurrentMap<String,ComponentDecorationToken> map = getComponentDecorationMap();
        List<String> removeForProject = new ArrayList<>();
        for (Entry<String,ComponentDecorationToken> token : map.entrySet()) {
            if (token.getValue().getProject().getProject().equals(project)) {
                removeForProject.add(token.getKey());
            }
        }
        for (String key : removeForProject) {
            map.remove(key);
        }
    }

    private static void parseChildrenForComponents(IPackageFragment pkg) throws JavaModelException, CoreException, BadLocationException {
        for (IJavaElement e : pkg.getChildren()) {
            if (e instanceof ICompilationUnit) {
                ICompilationUnit compUnit = (ICompilationUnit) e;
                if (!isComponentInImports(compUnit)) {
                    continue;
                }

                compUnit.getResource().deleteMarkers(BndtoolsConstants.MARKER_COMPONENT, true, 1);
                boolean hasComponent = hasComponentAnnotation(compUnit);
                if (hasComponent) {

                    beginASTParse(compUnit);
                }
            }
        }
    }

    private static void beginASTParse(ICompilationUnit compUnit) throws CoreException, JavaModelException, BadLocationException {
        ASTParser parser = ASTParser.newParser(AST.JLS8);
        parser.setKind(ASTParser.K_COMPILATION_UNIT);
        parser.setSource(compUnit);
        parser.setResolveBindings(true);
        final CompilationUnit ast = (CompilationUnit) parser.createAST(null);
        if (ast != null) {
            final Collection<Entry<String,Integer>> componentList = new ArrayList<>();
            ast.accept(new ASTVisitor() {

                @Override
                public boolean visit(MarkerAnnotation node) {

                    int lineNumber = ast.getLineNumber(node.getStartPosition());
                    componentList.add(new AbstractMap.SimpleEntry<>("OSGi Component", lineNumber));

                    return false;
                }

                @Override
                public boolean visit(NormalAnnotation node) {
                    String key = null;
                    List values = node.values();
                    for (int i = 0; i < values.size(); i++) {
                        MemberValuePair annotation = (MemberValuePair) values.get(i);
                        if (annotation.getName().toString().equals("name"))
                            key = "OSGi Component " + annotation.getValue().toString();
                        if (key != null)
                            break;
                    }
                    if (key == null)
                        key = "OSGi Component";
                    int lineNumber = ast.getLineNumber(node.getStartPosition());
                    componentList.add(new AbstractMap.SimpleEntry<>(key, lineNumber));

                    return false;
                }
            });

            createComponentMarkers(compUnit, componentList);

        }
    }

    private static boolean hasComponentAnnotation(ICompilationUnit c) throws CoreException, JavaModelException {
        boolean hasComponent = false;
        for (IType t : c.getTypes()) {
            for (IAnnotation annot : t.getAnnotations()) {
                if ("Component".equals(annot.getElementName())) {
                    hasComponent = true;
                    break;
                }

            }
            if (hasComponent)
                break;
        }
        return hasComponent;
    }

    private static void createComponentMarkers(ICompilationUnit c, Collection<Entry<String,Integer>> componentList) throws CoreException, JavaModelException, BadLocationException {
        boolean componentFound = componentList.size() > 0;
        for (Entry<String,Integer> entry : componentList) {
            String message = entry.getKey();
            Integer line = entry.getValue();
            IMarker marker = c.getResource().createMarker(BndtoolsConstants.MARKER_COMPONENT);
            marker.setAttribute(IMarker.SEVERITY, IMarker.SEVERITY_INFO);
            marker.setAttribute(IMarker.MESSAGE, message);
            marker.setAttribute(IMarker.LINE_NUMBER, line);
            marker.setAttribute(IMarker.LOCATION, "line " + line);

        }
        if (componentFound) {
            IPackageDeclaration[] decs = c.getPackageDeclarations();
            if (decs != null && decs.length > 0) {
                IPackageDeclaration dec = decs[0];
                if (dec != null) {
                    prepareDecorations(c, dec);
                }
            }
        }
    }

    private static void prepareDecorations(ICompilationUnit c, IPackageDeclaration dec) throws JavaModelException {

        final IType[] types = c.getTypes();
        if (types != null) {
            for (IType type : types) {
                String customText = null;
                boolean found = false;
                for (IAnnotation annot : type.getAnnotations()) {
                    if ("Component".equals(annot.getElementName())) {

                        customText = getNameFromComponent(annot);
                        found = true;
                        break;
                    }
                }
                if (found) {
                    ComponentDecorationToken token = new ComponentDecorationToken(dec.getElementName() + "." + c.getElementName(), customText, c.getJavaProject().getProject());
                    getComponentDecorationMap().put(type.getFullyQualifiedName(), token);
                } else {
                    getComponentDecorationMap().remove(type.getFullyQualifiedName());

                }

            }
        }

    }

    private static String getNameFromComponent(IAnnotation annot) throws JavaModelException {
        String customText = null;
        for (IMemberValuePair pair : annot.getMemberValuePairs()) {
            if ("name".equals(pair.getMemberName()) && pair.getValue() != null) {
                customText = String.valueOf(pair.getValue());
            }

        }
        return customText;
    }

    static class ComponentAnnotationLocator extends ASTVisitor {
        private final Collection<Map.Entry<String,ISourceRange>> sourceRanges = new ArrayList<Map.Entry<String,ISourceRange>>();

        public Collection<Map.Entry<String,ISourceRange>> getSourceRange() {
            return sourceRanges;
        }

        @Override
        public boolean visit(MarkerAnnotation node) {
            String key = "OSGi Component";
            ISourceRange value = new SourceRange(node.getStartPosition(), node.getLength());

            Map.Entry<String,ISourceRange> entry = new AbstractMap.SimpleEntry<String,ISourceRange>(key, value);
            sourceRanges.add(entry);
            return false;
        }

        @Override
        public boolean visit(NormalAnnotation node) {
            String key = null;
            List values = node.values();
            for (int i = 0; i < values.size(); i++) {
                MemberValuePair annotation = (MemberValuePair) values.get(i);
                if (annotation.getName().toString().equals("name"))
                    key = "OSGi Component " + annotation.getValue().toString();
                if (key != null)
                    break;
            }
            if (key == null)
                key = "OSGi Component";
            ISourceRange value = new SourceRange(node.getStartPosition(), node.getLength());

            Map.Entry<String,ISourceRange> entry = new AbstractMap.SimpleEntry<String,ISourceRange>(key, value);
            sourceRanges.add(entry);
            return false;
        }

    }

    private static boolean isComponentInImports(ICompilationUnit unit) throws CoreException {
        boolean annotationInImports = false;

        if (unit != null) {
            annotationInImports = isAnnotationInImports(unit);
        }

        return annotationInImports;
    }

    private static boolean isAnnotationInImports(ICompilationUnit unit) throws JavaModelException {
        boolean annotationInImports = false;
        for (IImportDeclaration importDecl : unit.getImports()) {
            annotationInImports = importDecl.getElementName().equals(ANNOTATION_COMPONENT_FQN) || importDecl.getElementName().equals(ANNOTATION_COMPONENT_PACKAGE + ".*");

            if (annotationInImports) {
                break;
            }
        }
        return annotationInImports;
    }

    public static class ComponentDecorationToken {
        private final String compilationUnitParentName;
        private final String customText;
        private final IProject project;

        public ComponentDecorationToken(String compilationUnitParentName, String customText, IProject project) {
            this.compilationUnitParentName = compilationUnitParentName;
            this.customText = customText;
            this.project = project;
        }

        public IProject getProject() {
            return project;
        }

        public String getCompilationUnitParentName() {
            return compilationUnitParentName;
        }

        public String getCustomText() {
            return customText;
        }
    }
}

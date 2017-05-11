package org.bndtools.builder.decorator.ui;

import org.bndtools.api.BndtoolsConstants;
import org.bndtools.api.ILogger;
import org.bndtools.api.Logger;
import org.bndtools.builder.BndtoolsBuilder;
import org.bndtools.builder.ComponentMarker;
import org.bndtools.builder.ComponentMarker.ComponentDecorationToken;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IImportDeclaration;
import org.eclipse.jdt.core.IPackageDeclaration;
import org.eclipse.jdt.internal.core.CompilationUnit;
import org.eclipse.jdt.internal.core.SourceType;
import org.eclipse.jface.resource.ImageDescriptor;
import org.eclipse.jface.viewers.IDecoration;
import org.eclipse.jface.viewers.ILightweightLabelDecorator;
import org.eclipse.jface.viewers.LabelProvider;
import org.eclipse.ui.plugin.AbstractUIPlugin;

/**
 * A decorator for {@link CompilationUnit}'s that adds an icon if the class contains an OSGi
 * {@link org.osgi.service.component.annotations.Component} annotation.
 *
 * @author wodencafe
 */
public class ComponentDecorator extends LabelProvider implements ILightweightLabelDecorator {
    private static final ILogger logger = Logger.getLogger(ComponentDecorator.class);
    private final ImageDescriptor componentIcon = AbstractUIPlugin.imageDescriptorFromPlugin(BndtoolsBuilder.PLUGIN_ID, "icons/component_s_flip.png");

    @Override
    public void decorate(Object element, IDecoration decoration) {

        try {

            if (element instanceof CompilationUnit) {

                CompilationUnit unit = (CompilationUnit) element;
                if (!unit.getJavaProject().getProject().hasNature(BndtoolsConstants.NATURE_ID)) {
                    return;
                }
                if (!isComponentInImports(unit)) {
                    return;
                }

                IPackageDeclaration[] decs = unit.getPackageDeclarations();
                if (decs != null && decs.length > 0) {
                    IPackageDeclaration dec = decs[0];
                    if (dec != null) {
                        String decCompName = dec.getElementName() + "." + unit.getElementName();
                        int count = 0;
                        String customText = null;
                        for (ComponentDecorationToken o : ComponentMarker.getComponentDecorationMap().values()) {
                            if (o.getCompilationUnitParentName().equals(decCompName)) {
                                count++;
                                if (count == 1) {
                                    customText = o.getCustomText();
                                }
                            }
                        }
                        if (count >= 1) {
                            if (customText == null)
                                decoration.addSuffix(" [Component]");
                            else
                                decoration.addSuffix(" [" + customText + "]");

                            decoration.addOverlay(componentIcon);

                        }

                    }
                }

            } else if (element instanceof SourceType) {
                SourceType type = (SourceType) element;
                if (!type.getJavaProject().getProject().hasNature(BndtoolsConstants.NATURE_ID)) {
                    return;
                }

                if (!isComponentInImports(type.getCompilationUnit())) {
                    return;
                }

                String fqn = type.getFullyQualifiedName();
                boolean found = ComponentMarker.getComponentDecorationMap().containsKey(fqn);
                if (found) {

                    ComponentDecorationToken token = ComponentMarker.getComponentDecorationMap().get(fqn);
                    String text = token.getCustomText();
                    if (text == null)
                        text = "Component";
                    decoration.addOverlay(componentIcon);
                    decoration.addSuffix(" [" + text + "]");
                }
            }

        } catch (CoreException e) {
            logger.logError("Component Decorator error", e);
        }
    }

    public boolean isComponentInImports(ICompilationUnit unit) throws CoreException {
        boolean annotationInImports = false;

        if (unit != null) {
            for (IImportDeclaration importDecl : unit.getImports()) {
                annotationInImports = importDecl.getElementName().equals(ComponentMarker.ANNOTATION_COMPONENT_FQN) || importDecl.getElementName().equals(ComponentMarker.ANNOTATION_COMPONENT_PACKAGE + ".*");
                if (annotationInImports) {
                    break;
                }
            }
        }
        return annotationInImports;
    }

}

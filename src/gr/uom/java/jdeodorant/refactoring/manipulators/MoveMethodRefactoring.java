package gr.uom.java.jdeodorant.refactoring.manipulators;

import gr.uom.java.ast.util.ExpressionExtractor;
import gr.uom.java.ast.util.MethodDeclarationUtility;
import gr.uom.java.ast.util.StatementExtractor;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.eclipse.core.filebuffers.FileBuffers;
import org.eclipse.core.filebuffers.ITextFileBuffer;
import org.eclipse.core.filebuffers.ITextFileBufferManager;
import org.eclipse.core.filebuffers.LocationKind;
import org.eclipse.core.resources.IFile;
import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.AbstractTypeDeclaration;
import org.eclipse.jdt.core.dom.Assignment;
import org.eclipse.jdt.core.dom.Block;
import org.eclipse.jdt.core.dom.CastExpression;
import org.eclipse.jdt.core.dom.ClassInstanceCreation;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.Expression;
import org.eclipse.jdt.core.dom.ExpressionStatement;
import org.eclipse.jdt.core.dom.FieldAccess;
import org.eclipse.jdt.core.dom.FieldDeclaration;
import org.eclipse.jdt.core.dom.IBinding;
import org.eclipse.jdt.core.dom.IMethodBinding;
import org.eclipse.jdt.core.dom.ITypeBinding;
import org.eclipse.jdt.core.dom.IVariableBinding;
import org.eclipse.jdt.core.dom.ImportDeclaration;
import org.eclipse.jdt.core.dom.InfixExpression;
import org.eclipse.jdt.core.dom.InstanceofExpression;
import org.eclipse.jdt.core.dom.MethodDeclaration;
import org.eclipse.jdt.core.dom.MethodInvocation;
import org.eclipse.jdt.core.dom.Modifier;
import org.eclipse.jdt.core.dom.Name;
import org.eclipse.jdt.core.dom.PackageDeclaration;
import org.eclipse.jdt.core.dom.ParameterizedType;
import org.eclipse.jdt.core.dom.PrimitiveType;
import org.eclipse.jdt.core.dom.QualifiedName;
import org.eclipse.jdt.core.dom.ReturnStatement;
import org.eclipse.jdt.core.dom.SimpleName;
import org.eclipse.jdt.core.dom.SingleVariableDeclaration;
import org.eclipse.jdt.core.dom.Statement;
import org.eclipse.jdt.core.dom.ThisExpression;
import org.eclipse.jdt.core.dom.Type;
import org.eclipse.jdt.core.dom.TypeDeclaration;
import org.eclipse.jdt.core.dom.TypeLiteral;
import org.eclipse.jdt.core.dom.VariableDeclarationFragment;
import org.eclipse.jdt.core.dom.VariableDeclarationStatement;
import org.eclipse.jdt.core.dom.rewrite.ASTRewrite;
import org.eclipse.jdt.core.dom.rewrite.ListRewrite;
import org.eclipse.jface.text.BadLocationException;
import org.eclipse.jface.text.IDocument;
import org.eclipse.text.edits.MalformedTreeException;
import org.eclipse.text.edits.TextEdit;
import org.eclipse.text.edits.UndoEdit;

public class MoveMethodRefactoring implements Refactoring {
	private IFile sourceFile;
	private IFile targetFile;
	private CompilationUnit sourceCompilationUnit;
	private CompilationUnit targetCompilationUnit;
	private TypeDeclaration sourceTypeDeclaration;
	private TypeDeclaration targetTypeDeclaration;
	private MethodDeclaration sourceMethod;
	private ASTRewrite sourceRewriter;
	private ASTRewrite targetRewriter;
	private UndoRefactoring undoRefactoring;
	private Set<ITypeBinding> requiredTargetImportDeclarationSet;
	private String targetClassVariableName;
	private Set<String> additionalArgumentsAddedToMovedMethod;
	private Map<MethodInvocation, MethodDeclaration> additionalMethodsToBeMoved;
	private boolean leaveDelegate;
	private String movedMethodName;
	
	public MoveMethodRefactoring(IFile sourceFile, IFile targetFile, CompilationUnit sourceCompilationUnit, CompilationUnit targetCompilationUnit, 
			TypeDeclaration sourceTypeDeclaration, TypeDeclaration targetTypeDeclaration, MethodDeclaration sourceMethod, Map<MethodInvocation,
			MethodDeclaration> additionalMethodsToBeMoved, boolean leaveDelegate, String movedMethodName) {
		this.sourceFile = sourceFile;
		this.targetFile = targetFile;
		this.sourceCompilationUnit = sourceCompilationUnit;
		this.targetCompilationUnit = targetCompilationUnit;
		this.sourceTypeDeclaration = sourceTypeDeclaration;
		this.targetTypeDeclaration = targetTypeDeclaration;
		this.sourceMethod = sourceMethod;
		this.undoRefactoring = new UndoRefactoring();
		this.requiredTargetImportDeclarationSet = new LinkedHashSet<ITypeBinding>();
		this.sourceRewriter = ASTRewrite.create(sourceTypeDeclaration.getAST());
		this.targetRewriter = ASTRewrite.create(targetCompilationUnit.getAST());
		this.targetClassVariableName = null;
		this.additionalArgumentsAddedToMovedMethod = new LinkedHashSet<String>();
		this.additionalMethodsToBeMoved = additionalMethodsToBeMoved;
		this.leaveDelegate = leaveDelegate;
		this.movedMethodName = movedMethodName;
	}

	public UndoRefactoring getUndoRefactoring() {
		return undoRefactoring;
	}

	public void apply() {
		if(sourceCompilationUnit.equals(targetCompilationUnit)) {
			int sourceTypeDeclarationFirstLevelPosition = -1;
			int targetTypeDeclarationFirstLevelPosition = -1;
			int sourceTypeDeclarationSecondLevelPosition = -1;
			int targetTypeDeclarationSecondLevelPosition = -1;
			List<AbstractTypeDeclaration> typeDeclarationList = sourceCompilationUnit.types();
			int i = 0;
			for(AbstractTypeDeclaration abstractTypeDeclaration : typeDeclarationList) {
				if(abstractTypeDeclaration instanceof TypeDeclaration) {
					TypeDeclaration parentTypeDeclaration = (TypeDeclaration)abstractTypeDeclaration;
					if(parentTypeDeclaration.equals(sourceTypeDeclaration)) {
						sourceTypeDeclarationFirstLevelPosition = i;
					}
					else {
						int secondLevelPosition = getPositionInParentTypeDeclaration(parentTypeDeclaration, sourceTypeDeclaration);
						if(secondLevelPosition != -1) {
							sourceTypeDeclarationFirstLevelPosition = i;
							sourceTypeDeclarationSecondLevelPosition = secondLevelPosition;
						}
					}
					if(parentTypeDeclaration.equals(targetTypeDeclaration)) {
						targetTypeDeclarationFirstLevelPosition = i;
					}
					else {
						int secondLevelPosition = getPositionInParentTypeDeclaration(parentTypeDeclaration, targetTypeDeclaration);
						if(secondLevelPosition != -1) {
							targetTypeDeclarationFirstLevelPosition = i;
							targetTypeDeclarationSecondLevelPosition = secondLevelPosition;
						}
					}
				}
				i++;
			}
			if(sourceTypeDeclarationFirstLevelPosition < targetTypeDeclarationFirstLevelPosition)
				applyTargetFirst();
			else if(sourceTypeDeclarationFirstLevelPosition > targetTypeDeclarationFirstLevelPosition)
				applySourceFirst();
			else {
				if(sourceTypeDeclarationSecondLevelPosition < targetTypeDeclarationSecondLevelPosition)
					applyTargetFirst();
				else if(sourceTypeDeclarationSecondLevelPosition > targetTypeDeclarationSecondLevelPosition)
					applySourceFirst();
			}
		}
		else {
			applyTargetFirst();
		}
	}

	private int getPositionInParentTypeDeclaration(TypeDeclaration parent, TypeDeclaration child) {
		TypeDeclaration[] types = parent.getTypes();
		int i = 0;
		for(TypeDeclaration type : types) {
			if(type.equals(child))
				return i;
			i++;
		}
		return -1;
	}

	public void applyTargetFirst() {
		if(!sourceCompilationUnit.equals(targetCompilationUnit))
			addRequiredTargetImportDeclarations();
		createMovedMethod();
		moveAdditionalMethods();
		//modifyMovedMethodInvocationInTargetClass();
		
		if(leaveDelegate) {
			addDelegationInSourceMethod();
		}
		else {
			removeSourceMethod();
		}
		modifyMovedMethodInvocationInSourceClass();
		
		ITextFileBufferManager bufferManager = FileBuffers.getTextFileBufferManager();
		ITextFileBuffer targetTextFileBuffer = bufferManager.getTextFileBuffer(targetFile.getFullPath(), LocationKind.IFILE);
		IDocument targetDocument = targetTextFileBuffer.getDocument();
		TextEdit targetEdit = targetRewriter.rewriteAST(targetDocument, null);
		try {
			UndoEdit undoEdit = targetEdit.apply(targetDocument, UndoEdit.CREATE_UNDO);
			undoRefactoring.put(targetFile, targetDocument, undoEdit);
		} catch (MalformedTreeException e) {
			e.printStackTrace();
		} catch (BadLocationException e) {
			e.printStackTrace();
		}
		
		ITextFileBuffer sourceTextFileBuffer = bufferManager.getTextFileBuffer(sourceFile.getFullPath(), LocationKind.IFILE);
		IDocument sourceDocument = sourceTextFileBuffer.getDocument();
		TextEdit sourceEdit = sourceRewriter.rewriteAST(sourceDocument, null);
		try {
			UndoEdit undoEdit = sourceEdit.apply(sourceDocument, UndoEdit.CREATE_UNDO);
			undoRefactoring.put(sourceFile, sourceDocument, undoEdit);
		} catch (MalformedTreeException e) {
			e.printStackTrace();
		} catch (BadLocationException e) {
			e.printStackTrace();
		}
	}

	public void applySourceFirst() {
		if(!sourceCompilationUnit.equals(targetCompilationUnit))
			addRequiredTargetImportDeclarations();
		createMovedMethod();
		moveAdditionalMethods();
		//modifyMovedMethodInvocationInTargetClass();
		
		if(leaveDelegate) {
			addDelegationInSourceMethod();
		}
		else {
			removeSourceMethod();
		}
		modifyMovedMethodInvocationInSourceClass();
		
		ITextFileBufferManager bufferManager = FileBuffers.getTextFileBufferManager();
		ITextFileBuffer sourceTextFileBuffer = bufferManager.getTextFileBuffer(sourceFile.getFullPath(), LocationKind.IFILE);
		IDocument sourceDocument = sourceTextFileBuffer.getDocument();
		TextEdit sourceEdit = sourceRewriter.rewriteAST(sourceDocument, null);
		try {
			UndoEdit undoEdit = sourceEdit.apply(sourceDocument, UndoEdit.CREATE_UNDO);
			undoRefactoring.put(sourceFile, sourceDocument, undoEdit);
		} catch (MalformedTreeException e) {
			e.printStackTrace();
		} catch (BadLocationException e) {
			e.printStackTrace();
		}
		
		ITextFileBuffer targetTextFileBuffer = bufferManager.getTextFileBuffer(targetFile.getFullPath(), LocationKind.IFILE);
		IDocument targetDocument = targetTextFileBuffer.getDocument();
		TextEdit targetEdit = targetRewriter.rewriteAST(targetDocument, null);
		try {
			UndoEdit undoEdit = targetEdit.apply(targetDocument, UndoEdit.CREATE_UNDO);
			undoRefactoring.put(targetFile, targetDocument, undoEdit);
		} catch (MalformedTreeException e) {
			e.printStackTrace();
		} catch (BadLocationException e) {
			e.printStackTrace();
		}
	}

	private void addRequiredTargetImportDeclarations() {
		List<ITypeBinding> typeBindings = new ArrayList<ITypeBinding>();
		Type returnType = sourceMethod.getReturnType2();
		ITypeBinding returnTypeBinding = returnType.resolveBinding();
		if(!typeBindings.contains(returnTypeBinding))
			typeBindings.add(returnTypeBinding);
		
		List<SingleVariableDeclaration> parameters = sourceMethod.parameters();
		for(SingleVariableDeclaration parameter : parameters) {
			Type parameterType = parameter.getType();
			ITypeBinding parameterTypeBinding = parameterType.resolveBinding();
			if(!typeBindings.contains(parameterTypeBinding))
				typeBindings.add(parameterTypeBinding);			
		}
		
		List<Name> thrownExceptions = sourceMethod.thrownExceptions();
		for(Name name : thrownExceptions) {
			IBinding binding = name.resolveBinding();
			if(binding.getKind() == IBinding.TYPE) {
				ITypeBinding typeBinding = (ITypeBinding)binding;
				if(!typeBindings.contains(typeBinding))
					typeBindings.add(typeBinding);
			}
		}
		
		ExpressionExtractor expressionExtractor = new ExpressionExtractor();
		List<Expression> variableInstructions = expressionExtractor.getVariableInstructions(sourceMethod.getBody());
		for(Expression variableInstruction : variableInstructions) {
			SimpleName simpleName = (SimpleName)variableInstruction;
			IBinding binding = simpleName.resolveBinding();
			if(binding.getKind() == IBinding.VARIABLE) {
				IVariableBinding variableBinding = (IVariableBinding)binding;
				ITypeBinding variableTypeBinding = variableBinding.getType();
				if(!typeBindings.contains(variableTypeBinding))
					typeBindings.add(variableTypeBinding);
				ITypeBinding declaringClassTypeBinding = variableBinding.getDeclaringClass();
				if(declaringClassTypeBinding != null && !typeBindings.contains(declaringClassTypeBinding))
					typeBindings.add(declaringClassTypeBinding);
			}
		}
		
		List<Expression> methodInvocations = expressionExtractor.getMethodInvocations(sourceMethod.getBody());
		for(Expression expression : methodInvocations) {
			if(expression instanceof MethodInvocation) {
				MethodInvocation methodInvocation = (MethodInvocation)expression;
				if(!additionalMethodsToBeMoved.keySet().contains(methodInvocation)) {
					IMethodBinding methodBinding = methodInvocation.resolveMethodBinding();
					ITypeBinding declaringClassTypeBinding = methodBinding.getDeclaringClass();
					if(declaringClassTypeBinding != null && !typeBindings.contains(declaringClassTypeBinding))
						typeBindings.add(declaringClassTypeBinding);
				}
			}
		}
		
		List<Expression> classInstanceCreations = expressionExtractor.getClassInstanceCreations(sourceMethod.getBody());
		for(Expression expression : classInstanceCreations) {
			ClassInstanceCreation classInstanceCreation = (ClassInstanceCreation)expression;
			Type classInstanceCreationType = classInstanceCreation.getType();
			ITypeBinding classInstanceCreationTypeBinding = classInstanceCreationType.resolveBinding();
			if(!typeBindings.contains(classInstanceCreationTypeBinding))
				typeBindings.add(classInstanceCreationTypeBinding);
		}
		
		List<Expression> typeLiterals = expressionExtractor.getTypeLiterals(sourceMethod.getBody());
		for(Expression expression : typeLiterals) {
			TypeLiteral typeLiteral = (TypeLiteral)expression;
			Type typeLiteralType = typeLiteral.getType();
			ITypeBinding typeLiteralTypeBinding = typeLiteralType.resolveBinding();
			if(!typeBindings.contains(typeLiteralTypeBinding))
				typeBindings.add(typeLiteralTypeBinding);
		}
		
		List<Expression> castExpressions = expressionExtractor.getCastExpressions(sourceMethod.getBody());
		for(Expression expression : castExpressions) {
			CastExpression castExpression = (CastExpression)expression;
			Type castExpressionType = castExpression.getType();
			ITypeBinding typeLiteralTypeBinding = castExpressionType.resolveBinding();
			if(!typeBindings.contains(typeLiteralTypeBinding))
				typeBindings.add(typeLiteralTypeBinding);
		}
		
		getSimpleTypeBindings(typeBindings);
		for(ITypeBinding typeBinding : requiredTargetImportDeclarationSet)
			addImportDeclaration(typeBinding);
	}

	private void getSimpleTypeBindings(List<ITypeBinding> typeBindings) {
		for(ITypeBinding typeBinding : typeBindings) {
			if(typeBinding.isPrimitive()) {
				
			}
			else if(typeBinding.isArray()) {
				ITypeBinding elementTypeBinding = typeBinding.getElementType();
				List<ITypeBinding> typeBindingList = new ArrayList<ITypeBinding>();
				typeBindingList.add(elementTypeBinding);
				getSimpleTypeBindings(typeBindingList);
			}
			else if(typeBinding.isParameterizedType()) {
				List<ITypeBinding> typeBindingList = new ArrayList<ITypeBinding>();
				typeBindingList.add(typeBinding.getTypeDeclaration());
				ITypeBinding[] typeArgumentBindings = typeBinding.getTypeArguments();
				for(ITypeBinding typeArgumentBinding : typeArgumentBindings)
					typeBindingList.add(typeArgumentBinding);
				getSimpleTypeBindings(typeBindingList);
			}
			else if(typeBinding.isWildcardType()) {
				List<ITypeBinding> typeBindingList = new ArrayList<ITypeBinding>();
				typeBindingList.add(typeBinding.getBound());
				getSimpleTypeBindings(typeBindingList);
			}
			else {
				if(typeBinding.isNested()) {
					requiredTargetImportDeclarationSet.add(typeBinding.getDeclaringClass());
				}
				requiredTargetImportDeclarationSet.add(typeBinding);
			}
		}
	}

	private void addImportDeclaration(ITypeBinding typeBinding) {
		String qualifiedName = typeBinding.getQualifiedName();
		String qualifiedPackageName = "";
		if(qualifiedName.contains("."))
			qualifiedPackageName = qualifiedName.substring(0,qualifiedName.lastIndexOf("."));
		PackageDeclaration targetPackageDeclaration = targetCompilationUnit.getPackage();
		String targetPackageDeclarationName = "";
		if(targetPackageDeclaration != null)
			targetPackageDeclarationName = targetPackageDeclaration.getName().getFullyQualifiedName();	
		if(!qualifiedPackageName.equals("") && !qualifiedPackageName.equals("java.lang") && !qualifiedPackageName.equals(targetPackageDeclarationName)) {
			List<ImportDeclaration> importDeclarationList = targetCompilationUnit.imports();
			boolean found = false;
			for(ImportDeclaration importDeclaration : importDeclarationList) {
				if(!importDeclaration.isOnDemand()) {
					if(qualifiedName.equals(importDeclaration.getName().getFullyQualifiedName())) {
						found = true;
						break;
					}
				}
				else {
					if(qualifiedPackageName.equals(importDeclaration.getName().getFullyQualifiedName())) {
						found = true;
						break;
					}
				}
			}
			if(!found) {
				AST ast = targetCompilationUnit.getAST();
				ImportDeclaration importDeclaration = ast.newImportDeclaration();
				targetRewriter.set(importDeclaration, ImportDeclaration.NAME_PROPERTY, ast.newName(qualifiedName), null);
				ListRewrite importRewrite = targetRewriter.getListRewrite(targetCompilationUnit, CompilationUnit.IMPORTS_PROPERTY);
				importRewrite.insertLast(importDeclaration, null);
			}
		}
	}

	private void createMovedMethod() {
		AST ast = targetTypeDeclaration.getAST();
		MethodDeclaration newMethodDeclaration = (MethodDeclaration)ASTNode.copySubtree(ast, sourceMethod);
		
		targetRewriter.set(newMethodDeclaration, MethodDeclaration.NAME_PROPERTY, ast.newSimpleName(movedMethodName), null);
		ListRewrite modifierRewrite = targetRewriter.getListRewrite(newMethodDeclaration, MethodDeclaration.MODIFIERS2_PROPERTY);
		Modifier publicModifier = newMethodDeclaration.getAST().newModifier(Modifier.ModifierKeyword.PUBLIC_KEYWORD);
		boolean modifierFound = false;
		List<Modifier> modifiers = newMethodDeclaration.modifiers();
		for(Modifier modifier : modifiers){
			if(modifier.getKeyword().equals(Modifier.ModifierKeyword.PUBLIC_KEYWORD)){
				modifierFound = true;
			}
			else if(modifier.getKeyword().equals(Modifier.ModifierKeyword.PRIVATE_KEYWORD) ||
					modifier.getKeyword().equals(Modifier.ModifierKeyword.PROTECTED_KEYWORD)){
				modifierFound = true;
				modifierRewrite.replace(modifier, publicModifier, null);
			}
		}
		if(!modifierFound){
			modifierRewrite.insertFirst(publicModifier, null);
		}
		
		ListRewrite parametersRewrite = targetRewriter.getListRewrite(newMethodDeclaration, MethodDeclaration.PARAMETERS_PROPERTY);
		List<SingleVariableDeclaration> sourceMethodParameters = sourceMethod.parameters();
		List<SingleVariableDeclaration> newMethodParameters = newMethodDeclaration.parameters();
		ExpressionExtractor expressionExtractor = new ExpressionExtractor();
		List<Expression> newVariableInstructions = expressionExtractor.getVariableInstructions(newMethodDeclaration.getBody());
		
		int i = 0;
		for(SingleVariableDeclaration parameter : sourceMethodParameters) {
			ITypeBinding parameterTypeBinding = parameter.getType().resolveBinding();
			if(parameterTypeBinding.isEqualTo(targetTypeDeclaration.resolveBinding())){
				for(Expression expression : newVariableInstructions) {
					SimpleName simpleName = (SimpleName)expression;
					if(parameter.getName().getIdentifier().equals(simpleName.getIdentifier())) {
						targetClassVariableName = parameter.getName().getIdentifier();
						parametersRewrite.remove(newMethodParameters.get(i), null);
						break;
					}
				}
				if(targetClassVariableName != null)
    				break;
			}
			i++;
		}
		
		FieldDeclaration[] fieldDeclarations = sourceTypeDeclaration.getFields();
		
		if(targetClassVariableName == null) {
        	for(FieldDeclaration fieldDeclaration : fieldDeclarations) {
        		List<VariableDeclarationFragment> fragments = fieldDeclaration.fragments();
        		for(VariableDeclarationFragment fragment : fragments) {
	        		if(fieldDeclaration.getType().resolveBinding().isEqualTo(targetTypeDeclaration.resolveBinding())) {
	        			for(Expression expression : newVariableInstructions) {
	        				SimpleName simpleName = (SimpleName)expression;
	        				if(fragment.getName().getIdentifier().equals(simpleName.getIdentifier())) {
	        					targetClassVariableName = fragment.getName().getIdentifier();
	        					break;
	        				}
	        			}
	        			if(targetClassVariableName != null)
	        				break;
	        		}
        		}
        		if(targetClassVariableName != null)
    				break;
        	}
		}
		
		List<Expression> oldMethodInvocations = expressionExtractor.getMethodInvocations(sourceMethod.getBody());
		MethodDeclaration[] methodDeclarations = sourceTypeDeclaration.getMethods();
		
		if(targetClassVariableName == null) {
			for(Expression oldMethodInvocation : oldMethodInvocations) {
				if(oldMethodInvocation instanceof MethodInvocation) {
					MethodInvocation methodInvocation = (MethodInvocation)oldMethodInvocation;
					if(methodInvocation.resolveMethodBinding().getDeclaringClass().isEqualTo(sourceTypeDeclaration.resolveBinding())) {
						for(MethodDeclaration methodDeclaration : methodDeclarations) {
							if(methodInvocation.resolveMethodBinding().isEqualTo(methodDeclaration.resolveBinding())) {
								SimpleName fieldInstruction = MethodDeclarationUtility.isGetter(methodDeclaration);
								if(fieldInstruction != null && fieldInstruction.resolveTypeBinding().isEqualTo(targetTypeDeclaration.resolveBinding())) {
									targetClassVariableName = fieldInstruction.getIdentifier();
									break;
								}
							}
						}
						if(targetClassVariableName != null)
	        				break;
					}
				}
			}
		}
		
		if(targetClassVariableName == null) {
			for(Expression oldMethodInvocation : oldMethodInvocations) {
				if(oldMethodInvocation instanceof MethodInvocation) {
					MethodInvocation methodInvocation = (MethodInvocation)oldMethodInvocation;
					if(methodInvocation.resolveMethodBinding().getDeclaringClass().isEqualTo(sourceTypeDeclaration.resolveBinding())) {
						for(MethodDeclaration methodDeclaration : methodDeclarations) {
							if(methodInvocation.resolveMethodBinding().isEqualTo(methodDeclaration.resolveBinding())) {
								MethodInvocation delegation = MethodDeclarationUtility.isDelegate(methodDeclaration);
								if(delegation != null && delegation.resolveMethodBinding().getDeclaringClass().isEqualTo(targetTypeDeclaration.resolveBinding())) {
									List<Expression> delegateMethodVariableInstructions = expressionExtractor.getVariableInstructions(methodDeclaration.getBody());
									for(Expression expression : delegateMethodVariableInstructions) {
										SimpleName fieldInstruction = (SimpleName)expression;
										IBinding fieldInstructionBinding = fieldInstruction.resolveBinding();
										if(fieldInstructionBinding.getKind() == IBinding.VARIABLE) {
											IVariableBinding fieldInstructionVariableBinding = (IVariableBinding)fieldInstructionBinding;
											if(fieldInstructionVariableBinding.isField() && fieldInstructionVariableBinding.getType().isEqualTo(targetTypeDeclaration.resolveBinding())) {
												targetClassVariableName = fieldInstruction.getIdentifier();
												break;
											}
										}
									}
									if(targetClassVariableName != null)
				        				break;
								}
							}
						}
						if(targetClassVariableName != null)
	        				break;
					}
				}
			}
		}
		
		if(targetClassVariableName == null) {
        	for(FieldDeclaration fieldDeclaration : fieldDeclarations) {
        		List<VariableDeclarationFragment> fragments = fieldDeclaration.fragments();
        		for(VariableDeclarationFragment fragment : fragments) {
	        		if(targetTypeDeclaration.resolveBinding().isEqualTo(fieldDeclaration.getType().resolveBinding().getSuperclass())) {
	        			for(Expression expression : newVariableInstructions) {
	        				SimpleName simpleName = (SimpleName)expression;
	        				if(fragment.getName().getIdentifier().equals(simpleName.getIdentifier())) {
	        					targetClassVariableName = fragment.getName().getIdentifier();
	        					break;
	        				}
	        			}
	        			if(targetClassVariableName != null)
	        				break;
	        		}
        		}
        		if(targetClassVariableName != null)
    				break;
        	}
		}
		
		modifySourceMemberAccessesInTargetClass(newMethodDeclaration);
		if(targetClassVariableName != null) {
			modifyTargetMethodInvocations(newMethodDeclaration);
			modifyTargetPublicFieldInstructions(newMethodDeclaration);
		}
		modifySourceStaticFieldInstructionsInTargetClass(newMethodDeclaration);
		modifySourceStaticMethodInvocationsInTargetClass(newMethodDeclaration);
		replaceTargetClassVariableNameWithThisExpressionInMethodInvocationArguments(newMethodDeclaration);
		replaceTargetClassVariableNameWithThisExpressionInClassInstanceCreationArguments(newMethodDeclaration);
		replaceTargetClassVariableNameWithThisExpressionInVariableDeclarationInitializers(newMethodDeclaration);
		replaceTargetClassVariableNameWithThisExpressionInInfixExpressions(newMethodDeclaration);
		replaceTargetClassVariableNameWithThisExpressionInCastExpressions(newMethodDeclaration);
		replaceTargetClassVariableNameWithThisExpressionInInstanceofExpressions(newMethodDeclaration);
		replaceTargetClassVariableNameWithThisExpressionInAssignments(newMethodDeclaration);
		replaceThisExpressionWithSourceClassParameterInMethodInvocationArguments(newMethodDeclaration);
		replaceThisExpressionWithSourceClassParameterInVariableDeclarationInitializers(newMethodDeclaration);

		ListRewrite targetClassBodyRewrite = targetRewriter.getListRewrite(targetTypeDeclaration, TypeDeclaration.BODY_DECLARATIONS_PROPERTY);
		targetClassBodyRewrite.insertLast(newMethodDeclaration, null);
	}

	private void moveAdditionalMethods() {
		AST ast = targetTypeDeclaration.getAST();
		Set<MethodDeclaration> methodsToBeMoved = new LinkedHashSet<MethodDeclaration>(additionalMethodsToBeMoved.values());
		for(MethodDeclaration methodDeclaration : methodsToBeMoved) {
			MethodDeclaration newMethodDeclaration = (MethodDeclaration)ASTNode.copySubtree(ast, methodDeclaration);
			ListRewrite targetClassBodyRewrite = targetRewriter.getListRewrite(targetTypeDeclaration, TypeDeclaration.BODY_DECLARATIONS_PROPERTY);
			targetClassBodyRewrite.insertLast(newMethodDeclaration, null);
			ListRewrite sourceClassBodyRewrite = sourceRewriter.getListRewrite(sourceTypeDeclaration, TypeDeclaration.BODY_DECLARATIONS_PROPERTY);
			sourceClassBodyRewrite.remove(methodDeclaration, null);
		}
	}

	private void removeSourceMethod() {
		ListRewrite classBodyRewrite = sourceRewriter.getListRewrite(sourceTypeDeclaration, TypeDeclaration.BODY_DECLARATIONS_PROPERTY);
		classBodyRewrite.remove(sourceMethod, null);
	}

	private void addDelegationInSourceMethod() {
		List<SingleVariableDeclaration> sourceMethodParameters = sourceMethod.parameters();
		String targetClassVariableName = null;
		for(SingleVariableDeclaration parameter : sourceMethodParameters) {
			ITypeBinding parameterTypeBinding = parameter.getType().resolveBinding();
			if(parameterTypeBinding.isEqualTo(targetTypeDeclaration.resolveBinding()) &&
					parameter.getName().getIdentifier().equals(this.targetClassVariableName)) {
				targetClassVariableName = parameter.getName().getIdentifier();
				break;
			}
		}
		FieldDeclaration[] fieldDeclarations = sourceTypeDeclaration.getFields();
		if(targetClassVariableName == null) {
        	for(FieldDeclaration fieldDeclaration : fieldDeclarations) {
        		List<VariableDeclarationFragment> fragments = fieldDeclaration.fragments();
        		for(VariableDeclarationFragment fragment : fragments) {
	        		if(fieldDeclaration.getType().resolveBinding().isEqualTo(targetTypeDeclaration.resolveBinding()) &&
	        				fragment.getName().getIdentifier().equals(this.targetClassVariableName)) {
	        			targetClassVariableName = fragment.getName().getIdentifier();
	        			break;
	        		}
        		}
        	}
		}
		if(targetClassVariableName == null) {
        	for(FieldDeclaration fieldDeclaration : fieldDeclarations) {
        		List<VariableDeclarationFragment> fragments = fieldDeclaration.fragments();
        		for(VariableDeclarationFragment fragment : fragments) {
	        		if(targetTypeDeclaration.resolveBinding().isEqualTo(fieldDeclaration.getType().resolveBinding().getSuperclass()) &&
	        				fragment.getName().getIdentifier().equals(this.targetClassVariableName)) {
	        			targetClassVariableName = fragment.getName().getIdentifier();
	        			break;
	        		}
        		}
        	}
		}
		
		ListRewrite methodBodyRewrite = sourceRewriter.getListRewrite(sourceMethod.getBody(), Block.STATEMENTS_PROPERTY);
		List<Statement> sourceMethodStatements = sourceMethod.getBody().statements();
		for(Statement statement : sourceMethodStatements) {
			methodBodyRewrite.remove(statement, null);
		}
		
		Type sourceMethodReturnType = sourceMethod.getReturnType2();
		ITypeBinding sourceMethodReturnTypeBinding = sourceMethodReturnType.resolveBinding();
		AST ast = sourceMethod.getBody().getAST();
		MethodInvocation delegation = ast.newMethodInvocation();
		sourceRewriter.set(delegation, MethodInvocation.NAME_PROPERTY, ast.newSimpleName(movedMethodName), null);
		SimpleName expressionName = ast.newSimpleName(targetClassVariableName);
		sourceRewriter.set(delegation, MethodInvocation.EXPRESSION_PROPERTY, expressionName, null);
		
		ListRewrite argumentRewrite = sourceRewriter.getListRewrite(delegation, MethodInvocation.ARGUMENTS_PROPERTY);
		for(SingleVariableDeclaration parameter : sourceMethodParameters) {
			if(!targetClassVariableName.equals(parameter.getName().getIdentifier())) {
				SimpleName argumentName = ast.newSimpleName(parameter.getName().getIdentifier());
				argumentRewrite.insertLast(argumentName, null);
			}
		}
		for(String argument : additionalArgumentsAddedToMovedMethod) {
			if(argument.equals("this"))
				argumentRewrite.insertLast(ast.newThisExpression(), null);
			else
				argumentRewrite.insertLast(ast.newSimpleName(argument), null);
		}
		if(sourceMethodReturnTypeBinding.getName().equals("void")) {
			ExpressionStatement expressionStatement = ast.newExpressionStatement(delegation);
			methodBodyRewrite.insertLast(expressionStatement, null);
		}
		else {
			ReturnStatement returnStatement = ast.newReturnStatement();
			sourceRewriter.set(returnStatement, ReturnStatement.EXPRESSION_PROPERTY, delegation, null);
			methodBodyRewrite.insertLast(returnStatement, null);
		}
	}

	private void modifyMovedMethodInvocationInSourceClass() {
		ExpressionExtractor expressionExtractor = new ExpressionExtractor();
		MethodDeclaration[] methodDeclarations = sourceTypeDeclaration.getMethods();
		for(MethodDeclaration methodDeclaration : methodDeclarations) {
			Block methodBody = methodDeclaration.getBody();
			if(methodBody != null) {
				List<Statement> statements = methodBody.statements();
				for(Statement statement : statements) {
					List<Expression> methodInvocations = expressionExtractor.getMethodInvocations(statement);
					for(Expression expression : methodInvocations) {
						if(expression instanceof MethodInvocation) {
							MethodInvocation methodInvocation = (MethodInvocation)expression;
							if(sourceMethod.resolveBinding().isEqualTo(methodInvocation.resolveMethodBinding())) {
								AST ast = methodInvocation.getAST();
								sourceRewriter.set(methodInvocation, MethodInvocation.NAME_PROPERTY, ast.newSimpleName(movedMethodName), null);
								List<Expression> arguments = methodInvocation.arguments();
								boolean foundInArguments = false;
								for(Expression argument : arguments) {
									if(argument.resolveTypeBinding().isEqualTo(targetTypeDeclaration.resolveBinding())) {
										foundInArguments = true;
										ListRewrite argumentRewrite = sourceRewriter.getListRewrite(methodInvocation, MethodInvocation.ARGUMENTS_PROPERTY);
										argumentRewrite.remove(argument, null);
										sourceRewriter.set(methodInvocation, MethodInvocation.EXPRESSION_PROPERTY, argument, null);
										break;
									}
								}
								boolean foundInFields = false;
								if(!foundInArguments) {
									FieldDeclaration[] fieldDeclarations = sourceTypeDeclaration.getFields();
									for(FieldDeclaration fieldDeclaration : fieldDeclarations) {
										List<VariableDeclarationFragment> fragments = fieldDeclaration.fragments();
										for(VariableDeclarationFragment fragment : fragments) {
											if(fieldDeclaration.getType().resolveBinding().isEqualTo(targetTypeDeclaration.resolveBinding()) &&
													fragment.getName().getIdentifier().equals(targetClassVariableName)) {
												foundInFields = true;
												sourceRewriter.set(methodInvocation, MethodInvocation.EXPRESSION_PROPERTY, fragment.getName(), null);
												break;
											}
										}
									}
								}
								if(!foundInArguments && !foundInFields) {
									FieldDeclaration[] fieldDeclarations = sourceTypeDeclaration.getFields();
									for(FieldDeclaration fieldDeclaration : fieldDeclarations) {
										List<VariableDeclarationFragment> fragments = fieldDeclaration.fragments();
										for(VariableDeclarationFragment fragment : fragments) {
											if(targetTypeDeclaration.resolveBinding().isEqualTo(fieldDeclaration.getType().resolveBinding().getSuperclass()) &&
													fragment.getName().getIdentifier().equals(targetClassVariableName)) {
												sourceRewriter.set(methodInvocation, MethodInvocation.EXPRESSION_PROPERTY, fragment.getName(), null);
												break;
											}
										}
									}
								}
								ListRewrite argumentRewrite = sourceRewriter.getListRewrite(methodInvocation, MethodInvocation.ARGUMENTS_PROPERTY);
								for(String argument : additionalArgumentsAddedToMovedMethod) {
									if(argument.equals("this"))
										argumentRewrite.insertLast(ast.newThisExpression(), null);
									else
										argumentRewrite.insertLast(ast.newSimpleName(argument), null);
								}
							}
						}
					}
				}
			}
		}
	}

	private void modifyMovedMethodInvocationInTargetClass() {
		ExpressionExtractor expressionExtractor = new ExpressionExtractor();
		MethodDeclaration[] methodDeclarations = targetTypeDeclaration.getMethods();
    	for(MethodDeclaration methodDeclaration : methodDeclarations) {
    		Block methodBody = methodDeclaration.getBody();
    		if(methodBody != null) {
	    		List<Statement> statements = methodBody.statements();
	    		Map<String, Integer> invokerCounterMap = new LinkedHashMap<String, Integer>();
	    		for(Statement statement : statements) {
	    			List<Expression> methodInvocations = expressionExtractor.getMethodInvocations(statement);
	    			for(Expression expression : methodInvocations) {
	    				if(expression instanceof MethodInvocation) {
	    					MethodInvocation methodInvocation = (MethodInvocation)expression;
	    					if(sourceMethod.resolveBinding().isEqualTo(methodInvocation.resolveMethodBinding())) {
	    						AST ast = methodInvocation.getAST();
	    						targetRewriter.set(methodInvocation, MethodInvocation.NAME_PROPERTY, ast.newSimpleName(movedMethodName), null);
	    						Expression invoker = methodInvocation.getExpression();
	    						if(invoker instanceof SimpleName) {
	    							SimpleName simpleName = (SimpleName)invoker;
	    							targetRewriter.remove(simpleName, null);
	    							String identifier = simpleName.getIdentifier();
	    							if(invokerCounterMap.containsKey(identifier)) {
	    								invokerCounterMap.put(identifier, invokerCounterMap.get(identifier)+1);
	    							}
	    							else {
	    								invokerCounterMap.put(identifier, 1);
	    							}
	    						}
	    						List<Expression> arguments = methodInvocation.arguments();
	    						boolean thisArgumentFound = false;
	    						for(Expression argument : arguments) {
	    							if(argument instanceof ThisExpression) {
	    								ListRewrite argumentRewrite = targetRewriter.getListRewrite(methodInvocation, MethodInvocation.ARGUMENTS_PROPERTY);
	    								argumentRewrite.remove(argument, null);
	    								thisArgumentFound = true;
	    							}
	    						}
	    						if(!thisArgumentFound) {
	    							for(Expression argument : arguments) {
	    								if(argument instanceof SimpleName) {
		    								SimpleName argumentSimpleName = (SimpleName)argument;
		    								if(argumentSimpleName.resolveTypeBinding().isEqualTo(targetTypeDeclaration.resolveBinding())) {
		    									ListRewrite argumentRewrite = targetRewriter.getListRewrite(methodInvocation, MethodInvocation.ARGUMENTS_PROPERTY);
			    								argumentRewrite.remove(argumentSimpleName, null);
			    								targetRewriter.set(methodInvocation, MethodInvocation.EXPRESSION_PROPERTY, argumentSimpleName, null);
		    								}
		    							}
	    							}
	    						}
	    					}
	    				}
	    			}
	    		}
    		}
    		/*
    		List<Expression> variableInstructions = expressionExtractor.getVariableInstructions(methodBody);
    		for(String invoker : invokerCounterMap.keySet()) {
    			int invokerCounter = 0;
    			for(Expression expression : variableInstructions) {
    				SimpleName simpleName = (SimpleName)expression;
    				if(simpleName.getIdentifier().equals(invoker))
    					invokerCounter++;
    			}
    			if(invokerCounter == invokerCounterMap.get(invoker)) {
    				List<SingleVariableDeclaration> parameters = methodDeclaration.parameters();
    				for(SingleVariableDeclaration parameter : parameters) {
    					if(parameter.getName().getIdentifier().equals(invoker)) {
    						ListRewrite parameterRewrite = targetRewriter.getListRewrite(methodDeclaration, MethodDeclaration.PARAMETERS_PROPERTY);
    						parameterRewrite.remove(parameter, null);
    					}
    				}
    			}
    		}
    		*/
    	}
	}

	private void modifyTargetMethodInvocations(MethodDeclaration newMethodDeclaration) {
		ExpressionExtractor extractor = new ExpressionExtractor();
		List<Expression> sourceMethodInvocations = extractor.getMethodInvocations(sourceMethod.getBody());
		List<Expression> newMethodInvocations = extractor.getMethodInvocations(newMethodDeclaration.getBody());
		int i = 0;
		for(Expression expression : sourceMethodInvocations) {
			if(expression instanceof MethodInvocation) {
				MethodInvocation methodInvocation = (MethodInvocation)expression;
				Expression methodInvocationExpression = methodInvocation.getExpression();
				if(methodInvocationExpression instanceof SimpleName) {
					SimpleName methodInvocationExpressionSimpleName = (SimpleName)methodInvocationExpression;
					if( (methodInvocationExpressionSimpleName.resolveTypeBinding().isEqualTo(targetTypeDeclaration.resolveBinding()) ||
							targetTypeDeclaration.resolveBinding().isEqualTo(methodInvocationExpressionSimpleName.resolveTypeBinding().getSuperclass()) ) &&
							methodInvocationExpressionSimpleName.getIdentifier().equals(targetClassVariableName)) {
						MethodInvocation newMethodInvocation = (MethodInvocation)newMethodInvocations.get(i);
						targetRewriter.remove(newMethodInvocation.getExpression(), null);
					}
				}
				else if(methodInvocationExpression instanceof FieldAccess) {
					FieldAccess methodInvocationExpressionFieldAccess = (FieldAccess)methodInvocationExpression;
					if( (methodInvocationExpressionFieldAccess.getName().resolveTypeBinding().isEqualTo(targetTypeDeclaration.resolveBinding()) ||
							targetTypeDeclaration.resolveBinding().isEqualTo(methodInvocationExpressionFieldAccess.getName().resolveTypeBinding().getSuperclass()) ) &&
							methodInvocationExpressionFieldAccess.getName().getIdentifier().equals(targetClassVariableName)) {
						MethodInvocation newMethodInvocation = (MethodInvocation)newMethodInvocations.get(i);
						targetRewriter.remove(newMethodInvocation.getExpression(), null);
					}
				}
			}
			i++;
		}
	}

	private void modifyTargetPublicFieldInstructions(MethodDeclaration newMethodDeclaration) {
		ExpressionExtractor extractor = new ExpressionExtractor();
		List<Expression> sourceFieldInstructions = extractor.getVariableInstructions(sourceMethod.getBody());
		List<Expression> newFieldInstructions = extractor.getVariableInstructions(newMethodDeclaration.getBody());
		FieldDeclaration[] fields = targetTypeDeclaration.getFields();
		for(FieldDeclaration field : fields) {
			List<VariableDeclarationFragment> fragments = field.fragments();
			for(VariableDeclarationFragment fragment : fragments) {
				SimpleName fragmentName = fragment.getName();
				int i = 0;
				for(Expression expression : sourceFieldInstructions) {
					SimpleName simpleName = (SimpleName)expression;
					if(simpleName.getParent() instanceof QualifiedName && fragmentName.getIdentifier().equals(simpleName.getIdentifier())) {
						QualifiedName qualifiedName = (QualifiedName)simpleName.getParent();
						if( (qualifiedName.getQualifier().resolveTypeBinding().isEqualTo(targetTypeDeclaration.resolveBinding()) ||
								targetTypeDeclaration.resolveBinding().isEqualTo(qualifiedName.getQualifier().resolveTypeBinding().getSuperclass()) ) &&
								qualifiedName.getQualifier().getFullyQualifiedName().equals(targetClassVariableName)) {
							SimpleName newSimpleName = (SimpleName)newFieldInstructions.get(i);
							targetRewriter.replace(newSimpleName.getParent(), simpleName, null);
						}
					}
					else if(simpleName.getParent() instanceof FieldAccess && fragmentName.getIdentifier().equals(simpleName.getIdentifier())) {
						FieldAccess fieldAccess = (FieldAccess)simpleName.getParent();
						Expression fieldAccessExpression = fieldAccess.getExpression();
						if(fieldAccessExpression instanceof FieldAccess) {
							FieldAccess invokerFieldAccess = (FieldAccess)fieldAccessExpression;
							if( (invokerFieldAccess.resolveTypeBinding().isEqualTo(targetTypeDeclaration.resolveBinding()) ||
									targetTypeDeclaration.resolveBinding().isEqualTo(invokerFieldAccess.resolveTypeBinding().getSuperclass())) &&
									invokerFieldAccess.getName().getIdentifier().equals(targetClassVariableName) && invokerFieldAccess.getExpression() instanceof ThisExpression) {
								SimpleName newSimpleName = (SimpleName)newFieldInstructions.get(i);
								FieldAccess newFieldAccess = (FieldAccess)newSimpleName.getParent();
								targetRewriter.replace(newFieldAccess.getExpression(), newMethodDeclaration.getAST().newThisExpression(), null);
							}
						}
					}
					i++;
				}
			}
		}
	}
	
	private void modifySourceStaticFieldInstructionsInTargetClass(MethodDeclaration newMethodDeclaration) {
		ExpressionExtractor extractor = new ExpressionExtractor();
		List<Expression> sourceVariableInstructions = extractor.getVariableInstructions(sourceMethod.getBody());
		List<Expression> newVariableInstructions = extractor.getVariableInstructions(newMethodDeclaration.getBody());
		int i = 0;
		for(Expression expression : sourceVariableInstructions) {
			SimpleName simpleName = (SimpleName)expression;
			IBinding binding = simpleName.resolveBinding();
			if(binding.getKind() == IBinding.VARIABLE) {
				IVariableBinding variableBinding = (IVariableBinding)binding;
				if(variableBinding.isField() && (variableBinding.getModifiers() & Modifier.STATIC) != 0 &&
						sourceTypeDeclaration.resolveBinding().isEqualTo(variableBinding.getDeclaringClass())) {
					AST ast = newMethodDeclaration.getAST();
					SimpleName qualifier = ast.newSimpleName(sourceTypeDeclaration.getName().getIdentifier());
					if(simpleName.getParent() instanceof FieldAccess) {
						FieldAccess fieldAccess = (FieldAccess)newVariableInstructions.get(i).getParent();
						targetRewriter.set(fieldAccess, FieldAccess.EXPRESSION_PROPERTY, qualifier, null);
					}
					else if(!(simpleName.getParent() instanceof QualifiedName)) {
						SimpleName newSimpleName = ast.newSimpleName(simpleName.getIdentifier());
						QualifiedName newQualifiedName = ast.newQualifiedName(qualifier, newSimpleName);
						targetRewriter.replace(newVariableInstructions.get(i), newQualifiedName, null);
					}
				}
			}
			i++;
		}
	}
	
	private void modifySourceStaticMethodInvocationsInTargetClass(MethodDeclaration newMethodDeclaration) {
		ExpressionExtractor extractor = new ExpressionExtractor();	
		List<Expression> sourceMethodInvocations = extractor.getMethodInvocations(sourceMethod.getBody());
		List<Expression> newMethodInvocations = extractor.getMethodInvocations(newMethodDeclaration.getBody());
		int i = 0;
		for(Expression expression : sourceMethodInvocations) {
			if(expression instanceof MethodInvocation) {
				MethodInvocation methodInvocation = (MethodInvocation)expression;
				IMethodBinding methodBinding = methodInvocation.resolveMethodBinding();
				if((methodBinding.getModifiers() & Modifier.STATIC) != 0 &&
						methodBinding.getDeclaringClass().isEqualTo(sourceTypeDeclaration.resolveBinding()) &&
						!additionalMethodsToBeMoved.containsKey(methodInvocation)) {
					AST ast = newMethodDeclaration.getAST();
					SimpleName qualifier = ast.newSimpleName(sourceTypeDeclaration.getName().getIdentifier());
					targetRewriter.set(newMethodInvocations.get(i), MethodInvocation.EXPRESSION_PROPERTY, qualifier, null);
				}
			}
			i++;
		}
	}

	private void modifySourceMemberAccessesInTargetClass(MethodDeclaration newMethodDeclaration) {
		ExpressionExtractor extractor = new ExpressionExtractor();	
		List<Expression> sourceMethodInvocations = extractor.getMethodInvocations(sourceMethod.getBody());
		List<Expression> newMethodInvocations = extractor.getMethodInvocations(newMethodDeclaration.getBody());
		List<Expression> expressionsToBeRemoved = new ArrayList<Expression>();
		for(MethodInvocation methodInvocation : additionalMethodsToBeMoved.keySet()) {
			for(Expression expression : sourceMethodInvocations) {
				if(expression instanceof MethodInvocation) {
					MethodInvocation sourceMethodInvocation = (MethodInvocation)expression;
					if(methodInvocation.equals(sourceMethodInvocation)) {
						expressionsToBeRemoved.add(methodInvocation);
					}
				}
			}
		}
		for(Expression expression : expressionsToBeRemoved) {
			int index = sourceMethodInvocations.indexOf(expression);
			sourceMethodInvocations.remove(index);
			newMethodInvocations.remove(index);
		}
		expressionsToBeRemoved.clear();
		int k = 0;
		for(Expression expression : sourceMethodInvocations) {
			if(expression instanceof MethodInvocation) {
				MethodInvocation methodInvocation = (MethodInvocation)expression;
				ITypeBinding methodInvocationDeclaringClassTypeBinding = methodInvocation.resolveMethodBinding().getDeclaringClass();
				if(methodInvocationDeclaringClassTypeBinding.isEqualTo(sourceTypeDeclaration.resolveBinding()) &&
						(methodInvocation.getExpression() == null || methodInvocation.getExpression() instanceof ThisExpression)) {
					MethodDeclaration[] sourceMethodDeclarations = sourceTypeDeclaration.getMethods();
					for(MethodDeclaration sourceMethodDeclaration : sourceMethodDeclarations) {
						if(sourceMethodDeclaration.resolveBinding().isEqualTo(methodInvocation.resolveMethodBinding())) {
							MethodInvocation delegation = MethodDeclarationUtility.isDelegate(sourceMethodDeclaration);
							if(delegation != null) {
								ITypeBinding delegationDeclaringClassTypeBinding = delegation.resolveMethodBinding().getDeclaringClass();
								if(delegationDeclaringClassTypeBinding.isEqualTo(targetTypeDeclaration.resolveBinding())) {
									if(delegation.getExpression() != null) {
										MethodInvocation newMethodInvocation = (MethodInvocation)ASTNode.copySubtree(newMethodDeclaration.getAST(), delegation);
										targetRewriter.remove(newMethodInvocation.getExpression(), null);
										targetRewriter.replace(newMethodInvocations.get(k), newMethodInvocation, null);
									}
									expressionsToBeRemoved.add(methodInvocation);
								}
							}
						}
					}
				}
				else if(methodInvocationDeclaringClassTypeBinding.isEqualTo(targetTypeDeclaration.resolveBinding()) && methodInvocation.getExpression() != null) {
					Expression methodInvocationExpression = methodInvocation.getExpression();
					if(methodInvocationExpression instanceof MethodInvocation) {
						MethodInvocation invoker = (MethodInvocation)methodInvocationExpression;
						if(invoker.getExpression() == null || invoker.getExpression() instanceof ThisExpression) {
							MethodDeclaration[] sourceMethodDeclarations = sourceTypeDeclaration.getMethods();
							for(MethodDeclaration sourceMethodDeclaration : sourceMethodDeclarations) {
								if(sourceMethodDeclaration.resolveBinding().isEqualTo(invoker.resolveMethodBinding())) {
									SimpleName fieldInstruction = MethodDeclarationUtility.isGetter(sourceMethodDeclaration);
									if(fieldInstruction != null && fieldInstruction.resolveTypeBinding().isEqualTo(targetTypeDeclaration.resolveBinding())) {
										int index = sourceMethodInvocations.indexOf(invoker);
										targetRewriter.remove(newMethodInvocations.get(index), null);
										expressionsToBeRemoved.add(invoker);
										expressionsToBeRemoved.add(methodInvocation);
									}
								}
							}
						}
					}
				}
			}
			k++;
		}
		for(Expression expression : expressionsToBeRemoved) {
			int index = sourceMethodInvocations.indexOf(expression);
			sourceMethodInvocations.remove(index);
			newMethodInvocations.remove(index);
		}
		
		List<Expression> sourceFieldInstructions = extractor.getVariableInstructions(sourceMethod.getBody());
		List<Expression> newFieldInstructions = extractor.getVariableInstructions(newMethodDeclaration.getBody());
		
		int i = 0;
		for(Expression expression : sourceFieldInstructions) {
			SimpleName simpleName = (SimpleName)expression;
			IBinding binding = simpleName.resolveBinding();
			if(binding.getKind() == IBinding.VARIABLE) {
				IVariableBinding variableBinding = (IVariableBinding)binding;
				if(variableBinding.isField() && (variableBinding.getModifiers() & Modifier.STATIC) == 0) {
					if(sourceTypeDeclaration.resolveBinding().isEqualTo(variableBinding.getDeclaringClass())) {
						SimpleName expressionName = (SimpleName)newFieldInstructions.get(i);
						if(expressionName.getParent() instanceof FieldAccess) {
							FieldAccess fieldAccess = (FieldAccess)expressionName.getParent();
							if(fieldAccess.getExpression() instanceof ThisExpression && !expressionName.getIdentifier().equals(targetClassVariableName)) {
								targetRewriter.replace(expressionName.getParent(), expressionName, null);
								if(!additionalArgumentsAddedToMovedMethod.contains(expressionName.getIdentifier()))
									addParameterToMovedMethod(newMethodDeclaration, expressionName);
							}
						}
						else if(!expressionName.getIdentifier().equals(targetClassVariableName) && !additionalArgumentsAddedToMovedMethod.contains(expressionName.getIdentifier()))
							addParameterToMovedMethod(newMethodDeclaration, expressionName);
					}
					else {
						Type superclassType = sourceTypeDeclaration.getSuperclassType();
						ITypeBinding superclassTypeBinding = null;
						if(superclassType != null)
							superclassTypeBinding = superclassType.resolveBinding();
						while(superclassTypeBinding != null && !superclassTypeBinding.isEqualTo(variableBinding.getDeclaringClass())) {
							superclassTypeBinding = superclassTypeBinding.getSuperclass();
						}
						if(superclassTypeBinding != null) {
							IVariableBinding[] superclassFieldBindings = superclassTypeBinding.getDeclaredFields();
							for(IVariableBinding superclassFieldBinding : superclassFieldBindings) {
								if(superclassFieldBinding.isEqualTo(variableBinding)) {
									SimpleName expressionName = (SimpleName)newFieldInstructions.get(i);
									if(!expressionName.getIdentifier().equals(targetClassVariableName) && !additionalArgumentsAddedToMovedMethod.contains(expressionName.getIdentifier()))
										addParameterToMovedMethod(newMethodDeclaration, variableBinding);
								}
							}
						}
					}
				}
			}
			i++;
		}
		SimpleName parameterName = null;
		Set<String> sourceMethodsWithPublicModifier = new LinkedHashSet<String>();
		int j = 0;
		for(Expression expression : sourceMethodInvocations) {
			if(expression instanceof MethodInvocation) {
				MethodInvocation methodInvocation = (MethodInvocation)expression;
				if(methodInvocation.getExpression() == null || methodInvocation.getExpression() instanceof ThisExpression) {
					IMethodBinding methodBinding = methodInvocation.resolveMethodBinding();
					if(methodBinding.getDeclaringClass().isEqualTo(sourceTypeDeclaration.resolveBinding())) {
						MethodDeclaration[] sourceMethodDeclarations = sourceTypeDeclaration.getMethods();
						for(MethodDeclaration sourceMethodDeclaration : sourceMethodDeclarations) {
							if(sourceMethodDeclaration.resolveBinding().isEqualTo(methodInvocation.resolveMethodBinding()) &&
									!sourceMethod.resolveBinding().isEqualTo(methodInvocation.resolveMethodBinding())) {
								SimpleName fieldName = MethodDeclarationUtility.isGetter(sourceMethodDeclaration);
								int modifiers = sourceMethodDeclaration.getModifiers();
								MethodInvocation newMethodInvocation = (MethodInvocation)newMethodInvocations.get(j);
								if((modifiers & Modifier.STATIC) != 0) {
									AST ast = newMethodDeclaration.getAST();
									targetRewriter.set(newMethodInvocation, MethodInvocation.EXPRESSION_PROPERTY, ast.newSimpleName(sourceTypeDeclaration.getName().getIdentifier()), null);
									if(!sourceMethodsWithPublicModifier.contains(methodInvocation.resolveMethodBinding().toString())) {
										setPublicModifierToSourceMethod(methodInvocation);
										sourceMethodsWithPublicModifier.add(methodInvocation.resolveMethodBinding().toString());
									}
								}
								else if(fieldName != null) {
									AST ast = newMethodDeclaration.getAST();
									targetRewriter.replace(newMethodInvocation, ast.newSimpleName(fieldName.getIdentifier()), null);
									if(!fieldName.getIdentifier().equals(targetClassVariableName) && !additionalArgumentsAddedToMovedMethod.contains(fieldName.getIdentifier()))
										addParameterToMovedMethod(newMethodDeclaration, fieldName);
								}
								else {
									if(!additionalArgumentsAddedToMovedMethod.contains("this")) {
										parameterName = addSourceClassParameterToMovedMethod(newMethodDeclaration);
									}
									targetRewriter.set(newMethodInvocation, MethodInvocation.EXPRESSION_PROPERTY, parameterName, null);
									if(!sourceMethodsWithPublicModifier.contains(methodInvocation.resolveMethodBinding().toString())) {
										setPublicModifierToSourceMethod(methodInvocation);
										sourceMethodsWithPublicModifier.add(methodInvocation.resolveMethodBinding().toString());
									}
								}
							}
						}
					}
					else {
						Type superclassType = sourceTypeDeclaration.getSuperclassType();
						ITypeBinding superclassTypeBinding = null;
						if(superclassType != null)
							superclassTypeBinding = superclassType.resolveBinding();
						while(superclassTypeBinding != null && !methodBinding.getDeclaringClass().isEqualTo(superclassTypeBinding)) {
							superclassTypeBinding = superclassTypeBinding.getSuperclass();
						}
						if(superclassTypeBinding != null) {
							IMethodBinding[] superclassMethodBindings = superclassTypeBinding.getDeclaredMethods();
							for(IMethodBinding superclassMethodBinding : superclassMethodBindings) {
								if(superclassMethodBinding.isEqualTo(methodBinding)) {
									MethodInvocation newMethodInvocation = (MethodInvocation)newMethodInvocations.get(j);
									if(!additionalArgumentsAddedToMovedMethod.contains("this")) {
										parameterName = addSourceClassParameterToMovedMethod(newMethodDeclaration);
									}
									targetRewriter.set(newMethodInvocation, MethodInvocation.EXPRESSION_PROPERTY, parameterName, null);
								}
							}
						}
					}
				}
			}
			j++;
		}
	}

	private SimpleName addSourceClassParameterToMovedMethod(MethodDeclaration newMethodDeclaration) {
		AST ast = newMethodDeclaration.getAST();
		SingleVariableDeclaration parameter = ast.newSingleVariableDeclaration();
		SimpleName typeName = ast.newSimpleName(sourceTypeDeclaration.getName().getIdentifier());
		Type parameterType = ast.newSimpleType(typeName);
		targetRewriter.set(parameter, SingleVariableDeclaration.TYPE_PROPERTY, parameterType, null);
		String sourceTypeName = sourceTypeDeclaration.getName().getIdentifier();
		SimpleName parameterName = ast.newSimpleName(sourceTypeName.replaceFirst(Character.toString(sourceTypeName.charAt(0)), Character.toString(Character.toLowerCase(sourceTypeName.charAt(0)))));
		targetRewriter.set(parameter, SingleVariableDeclaration.NAME_PROPERTY, parameterName, null);
		ListRewrite parametersRewrite = targetRewriter.getListRewrite(newMethodDeclaration, MethodDeclaration.PARAMETERS_PROPERTY);
		parametersRewrite.insertLast(parameter, null);
		this.additionalArgumentsAddedToMovedMethod.add("this");
		return parameterName;
	}

	private void addParameterToMovedMethod(MethodDeclaration newMethodDeclaration, SimpleName fieldName) {
		AST ast = newMethodDeclaration.getAST();
		SingleVariableDeclaration parameter = ast.newSingleVariableDeclaration();
		Type fieldType = null;
		FieldDeclaration[] fields = sourceTypeDeclaration.getFields();
		for(FieldDeclaration field : fields) {
			List<VariableDeclarationFragment> fragments = field.fragments();
			for(VariableDeclarationFragment fragment : fragments) {
				if(fragment.getName().getIdentifier().equals(fieldName.getIdentifier())) {
					fieldType = field.getType();
					break;
				}
			}
		}
		targetRewriter.set(parameter, SingleVariableDeclaration.TYPE_PROPERTY, fieldType, null);
		targetRewriter.set(parameter, SingleVariableDeclaration.NAME_PROPERTY, ast.newSimpleName(fieldName.getIdentifier()), null);
		ListRewrite parametersRewrite = targetRewriter.getListRewrite(newMethodDeclaration, MethodDeclaration.PARAMETERS_PROPERTY);
		parametersRewrite.insertLast(parameter, null);
		this.additionalArgumentsAddedToMovedMethod.add(fieldName.getIdentifier());
	}

	private void addParameterToMovedMethod(MethodDeclaration newMethodDeclaration, IVariableBinding variableBinding) {
		AST ast = newMethodDeclaration.getAST();
		SingleVariableDeclaration parameter = ast.newSingleVariableDeclaration();
		ITypeBinding typeBinding = variableBinding.getType();
		Type fieldType = null;
		if(typeBinding.isClass()) {
			fieldType = ast.newSimpleType(ast.newSimpleName(typeBinding.getName()));
		}
		else if(typeBinding.isPrimitive()) {
			String primitiveType = typeBinding.getName();
			if(primitiveType.equals("int"))
				fieldType = ast.newPrimitiveType(PrimitiveType.INT);
			else if(primitiveType.equals("double"))
				fieldType = ast.newPrimitiveType(PrimitiveType.DOUBLE);
			else if(primitiveType.equals("byte"))
				fieldType = ast.newPrimitiveType(PrimitiveType.BYTE);
			else if(primitiveType.equals("short"))
				fieldType = ast.newPrimitiveType(PrimitiveType.SHORT);
			else if(primitiveType.equals("char"))
				fieldType = ast.newPrimitiveType(PrimitiveType.CHAR);
			else if(primitiveType.equals("long"))
				fieldType = ast.newPrimitiveType(PrimitiveType.LONG);
			else if(primitiveType.equals("float"))
				fieldType = ast.newPrimitiveType(PrimitiveType.FLOAT);
			else if(primitiveType.equals("boolean"))
				fieldType = ast.newPrimitiveType(PrimitiveType.BOOLEAN);
		}
		else if(typeBinding.isArray()) {
			ITypeBinding elementTypeBinding = typeBinding.getElementType();
			Type elementType = ast.newSimpleType(ast.newSimpleName(elementTypeBinding.getName()));
			fieldType = ast.newArrayType(elementType, typeBinding.getDimensions());
		}
		else if(typeBinding.isParameterizedType()) {
			fieldType = createParameterizedType(ast, typeBinding);
		}
		targetRewriter.set(parameter, SingleVariableDeclaration.TYPE_PROPERTY, fieldType, null);
		targetRewriter.set(parameter, SingleVariableDeclaration.NAME_PROPERTY, ast.newSimpleName(variableBinding.getName()), null);
		ListRewrite parametersRewrite = targetRewriter.getListRewrite(newMethodDeclaration, MethodDeclaration.PARAMETERS_PROPERTY);
		parametersRewrite.insertLast(parameter, null);
		this.additionalArgumentsAddedToMovedMethod.add(variableBinding.getName());
	}

	private ParameterizedType createParameterizedType(AST ast, ITypeBinding typeBinding) {
		ITypeBinding erasure = typeBinding.getErasure();
		ITypeBinding[] typeArguments = typeBinding.getTypeArguments();
		ParameterizedType parameterizedType = ast.newParameterizedType(ast.newSimpleType(ast.newSimpleName(erasure.getName())));
		ListRewrite typeArgumentsRewrite = targetRewriter.getListRewrite(parameterizedType, ParameterizedType.TYPE_ARGUMENTS_PROPERTY);
		for(ITypeBinding typeArgument : typeArguments) {
			if(typeArgument.isClass())
				typeArgumentsRewrite.insertLast(ast.newSimpleType(ast.newSimpleName(typeArgument.getName())), null);
			else if(typeArgument.isParameterizedType()) {
				typeArgumentsRewrite.insertLast(createParameterizedType(ast, typeArgument), null);
			}
		}
		return parameterizedType;
	}

	private void setPublicModifierToSourceField(SimpleName simpleName) {
		FieldDeclaration[] fieldDeclarations = sourceTypeDeclaration.getFields();
		for(FieldDeclaration fieldDeclaration : fieldDeclarations) {
			if(fieldDeclaration.getType().resolveBinding().isEqualTo(simpleName.resolveTypeBinding())) {
				List<VariableDeclarationFragment> fragments = fieldDeclaration.fragments();
				for(VariableDeclarationFragment fragment : fragments) {
					if(fragment.getName().getIdentifier().equals(simpleName.getIdentifier())) {
						ListRewrite modifierRewrite = sourceRewriter.getListRewrite(fieldDeclaration, FieldDeclaration.MODIFIERS2_PROPERTY);
						Modifier publicModifier = fieldDeclaration.getAST().newModifier(Modifier.ModifierKeyword.PUBLIC_KEYWORD);
						boolean modifierFound = false;
						List<Modifier> modifiers = fieldDeclaration.modifiers();
						for(Modifier modifier : modifiers){
							if(modifier.getKeyword().equals(Modifier.ModifierKeyword.PUBLIC_KEYWORD)){
								modifierFound = true;
							}
							else if(modifier.getKeyword().equals(Modifier.ModifierKeyword.PRIVATE_KEYWORD) ||
									modifier.getKeyword().equals(Modifier.ModifierKeyword.PROTECTED_KEYWORD)){
								modifierFound = true;
								modifierRewrite.replace(modifier, publicModifier, null);
							}
						}
						if(!modifierFound){
							modifierRewrite.insertFirst(publicModifier, null);
						}
					}
				}
			}
		}
	}

	private void setPublicModifierToSourceMethod(MethodInvocation methodInvocation) {
		MethodDeclaration[] methodDeclarations = sourceTypeDeclaration.getMethods();
		for(MethodDeclaration methodDeclaration : methodDeclarations) {
			if(methodDeclaration.resolveBinding().isEqualTo(methodInvocation.resolveMethodBinding())) {
				ListRewrite modifierRewrite = sourceRewriter.getListRewrite(methodDeclaration, MethodDeclaration.MODIFIERS2_PROPERTY);
				Modifier publicModifier = methodDeclaration.getAST().newModifier(Modifier.ModifierKeyword.PUBLIC_KEYWORD);
				boolean modifierFound = false;
				List<Modifier> modifiers = methodDeclaration.modifiers();
				for(Modifier modifier : modifiers){
					if(modifier.getKeyword().equals(Modifier.ModifierKeyword.PUBLIC_KEYWORD)){
						modifierFound = true;
					}
					else if(modifier.getKeyword().equals(Modifier.ModifierKeyword.PRIVATE_KEYWORD) ||
							modifier.getKeyword().equals(Modifier.ModifierKeyword.PROTECTED_KEYWORD)){
						modifierFound = true;
						modifierRewrite.replace(modifier, publicModifier, null);
					}
				}
				if(!modifierFound){
					modifierRewrite.insertFirst(publicModifier, null);
				}
			}
		}
	}
	
	private void replaceTargetClassVariableNameWithThisExpressionInMethodInvocationArguments(MethodDeclaration newMethodDeclaration) {
		ExpressionExtractor extractor = new ExpressionExtractor();
		List<Expression> sourceMethodInvocations = extractor.getMethodInvocations(sourceMethod.getBody());
		List<Expression> newMethodInvocations = extractor.getMethodInvocations(newMethodDeclaration.getBody());
		int i = 0;
		for(Expression invocation : newMethodInvocations) {
			if(invocation instanceof MethodInvocation) {
				MethodInvocation methodInvocation = (MethodInvocation)invocation;
				List<Expression> arguments = methodInvocation.arguments();
				for(Expression argument : arguments) {
					if(argument instanceof SimpleName) {
						SimpleName simpleNameArgument = (SimpleName)argument;
						if(simpleNameArgument.getIdentifier().equals(targetClassVariableName)) {
							ListRewrite argumentRewrite = targetRewriter.getListRewrite(methodInvocation, MethodInvocation.ARGUMENTS_PROPERTY);
							MethodInvocation sourceMethodInvocation = (MethodInvocation)sourceMethodInvocations.get(i);
							AST ast = newMethodDeclaration.getAST();
							if(sourceMethod.resolveBinding().isEqualTo(sourceMethodInvocation.resolveMethodBinding())) {
								targetRewriter.set(methodInvocation, MethodInvocation.NAME_PROPERTY, ast.newSimpleName(movedMethodName), null);
								argumentRewrite.remove(argument, null);
							}
							else {
								argumentRewrite.replace(argument, ast.newThisExpression(), null);
							}
						}
					}
					else if(argument instanceof FieldAccess) {
						FieldAccess fieldAccess = (FieldAccess)argument;
						SimpleName simpleNameArgument = fieldAccess.getName();
						if(simpleNameArgument.getIdentifier().equals(targetClassVariableName)) {
							ListRewrite argumentRewrite = targetRewriter.getListRewrite(methodInvocation, MethodInvocation.ARGUMENTS_PROPERTY);
							MethodInvocation sourceMethodInvocation = (MethodInvocation)sourceMethodInvocations.get(i);
							AST ast = newMethodDeclaration.getAST();
							if(sourceMethod.resolveBinding().isEqualTo(sourceMethodInvocation.resolveMethodBinding())) {
								targetRewriter.set(methodInvocation, MethodInvocation.NAME_PROPERTY, ast.newSimpleName(movedMethodName), null);
								argumentRewrite.remove(argument, null);
							}
							else {
								argumentRewrite.replace(argument, ast.newThisExpression(), null);
							}
						}
					}
				}
			}
			i++;
		}
	}
	
	private void replaceTargetClassVariableNameWithThisExpressionInClassInstanceCreationArguments(MethodDeclaration newMethodDeclaration) {
		ExpressionExtractor extractor = new ExpressionExtractor();
		List<Expression> classInstanceCreations = extractor.getClassInstanceCreations(newMethodDeclaration.getBody());
		for(Expression creation : classInstanceCreations) {
			ClassInstanceCreation classInstanceCreation = (ClassInstanceCreation)creation;
			List<Expression> arguments = classInstanceCreation.arguments();
			for(Expression argument : arguments) {
				if(argument instanceof SimpleName) {
					SimpleName simpleNameArgument = (SimpleName)argument;
					if(simpleNameArgument.getIdentifier().equals(targetClassVariableName)) {
						ListRewrite argumentRewrite = targetRewriter.getListRewrite(classInstanceCreation, ClassInstanceCreation.ARGUMENTS_PROPERTY);
						AST ast = newMethodDeclaration.getAST();
						argumentRewrite.replace(argument, ast.newThisExpression(), null);
					}
				}
				else if(argument instanceof FieldAccess) {
					FieldAccess fieldAccess = (FieldAccess)argument;
					SimpleName simpleNameArgument = fieldAccess.getName();
					if(simpleNameArgument.getIdentifier().equals(targetClassVariableName)) {
						ListRewrite argumentRewrite = targetRewriter.getListRewrite(classInstanceCreation, ClassInstanceCreation.ARGUMENTS_PROPERTY);
						AST ast = newMethodDeclaration.getAST();
						argumentRewrite.replace(argument, ast.newThisExpression(), null);
					}
				}
			}
		}
	}
	
	private void replaceTargetClassVariableNameWithThisExpressionInVariableDeclarationInitializers(MethodDeclaration newMethodDeclaration) {
		StatementExtractor extractor = new StatementExtractor();
		List<Statement> variableDeclarations = extractor.getVariableDeclarations(newMethodDeclaration.getBody());
		for(Statement declaration : variableDeclarations) {
			VariableDeclarationStatement variableDeclaration = (VariableDeclarationStatement)declaration;
			List<VariableDeclarationFragment> fragments = variableDeclaration.fragments();
			for(VariableDeclarationFragment fragment : fragments) {
				Expression initializer = fragment.getInitializer();
				if(initializer instanceof SimpleName) {
					SimpleName simpleNameInitializer = (SimpleName)initializer;
					if(simpleNameInitializer.getIdentifier().equals(targetClassVariableName)) {
						AST ast = newMethodDeclaration.getAST();
						targetRewriter.set(fragment, VariableDeclarationFragment.INITIALIZER_PROPERTY, ast.newThisExpression(), null);
					}
				}
				else if(initializer instanceof FieldAccess) {
					FieldAccess fieldAccess = (FieldAccess)initializer;
					SimpleName simpleNameInitializer = fieldAccess.getName();
					if(simpleNameInitializer.getIdentifier().equals(targetClassVariableName)) {
						AST ast = newMethodDeclaration.getAST();
						targetRewriter.set(fragment, VariableDeclarationFragment.INITIALIZER_PROPERTY, ast.newThisExpression(), null);
					}
				}
			}
		}
	}
	
	private void replaceTargetClassVariableNameWithThisExpressionInInfixExpressions(MethodDeclaration newMethodDeclaration) {
		ExpressionExtractor extractor = new ExpressionExtractor();
		List<Expression> infixExpressions = extractor.getInfixExpressions(newMethodDeclaration.getBody());
		for(Expression expression : infixExpressions) {
			InfixExpression infixExpression = (InfixExpression)expression;
			if(infixExpression.getLeftOperand() instanceof SimpleName) {
				SimpleName leftOperand = (SimpleName)infixExpression.getLeftOperand();
				if(leftOperand.getIdentifier().equals(targetClassVariableName)) {
					AST ast = newMethodDeclaration.getAST();
					targetRewriter.set(infixExpression, InfixExpression.LEFT_OPERAND_PROPERTY, ast.newThisExpression(), null);
				}	
			}
			else if(infixExpression.getLeftOperand() instanceof FieldAccess) {
				FieldAccess fieldAccess = (FieldAccess)infixExpression.getLeftOperand();
				SimpleName leftOperand = fieldAccess.getName();
				if(leftOperand.getIdentifier().equals(targetClassVariableName)) {
					AST ast = newMethodDeclaration.getAST();
					targetRewriter.set(infixExpression, InfixExpression.LEFT_OPERAND_PROPERTY, ast.newThisExpression(), null);
				}
			}
			if(infixExpression.getRightOperand() instanceof SimpleName) {
				SimpleName rightOperand = (SimpleName)infixExpression.getRightOperand();
				if(rightOperand.getIdentifier().equals(targetClassVariableName)) {
					AST ast = newMethodDeclaration.getAST();
					targetRewriter.set(infixExpression, InfixExpression.RIGHT_OPERAND_PROPERTY, ast.newThisExpression(), null);
				}	
			}
			else if(infixExpression.getRightOperand() instanceof FieldAccess) {
				FieldAccess fieldAccess = (FieldAccess)infixExpression.getRightOperand();
				SimpleName rightOperand = fieldAccess.getName();
				if(rightOperand.getIdentifier().equals(targetClassVariableName)) {
					AST ast = newMethodDeclaration.getAST();
					targetRewriter.set(infixExpression, InfixExpression.RIGHT_OPERAND_PROPERTY, ast.newThisExpression(), null);
				}
			}
		}
	}
	
	private void replaceTargetClassVariableNameWithThisExpressionInCastExpressions(MethodDeclaration newMethodDeclaration) {
		ExpressionExtractor extractor = new ExpressionExtractor();
		List<Expression> castExpressions = extractor.getCastExpressions(newMethodDeclaration.getBody());
		for(Expression expression : castExpressions) {
			CastExpression castExpression = (CastExpression)expression;
			if(castExpression.getExpression() instanceof SimpleName) {
				SimpleName simpleName = (SimpleName)castExpression.getExpression();
				if(simpleName.getIdentifier().equals(targetClassVariableName)) {
					AST ast = newMethodDeclaration.getAST();
					targetRewriter.set(castExpression, CastExpression.EXPRESSION_PROPERTY, ast.newThisExpression(), null);
				}
			}
			else if(castExpression.getExpression() instanceof FieldAccess) {
				FieldAccess fieldAccess = (FieldAccess)castExpression.getExpression();
				SimpleName simpleName = fieldAccess.getName();
				if(simpleName.getIdentifier().equals(targetClassVariableName)) {
					AST ast = newMethodDeclaration.getAST();
					targetRewriter.set(castExpression, CastExpression.EXPRESSION_PROPERTY, ast.newThisExpression(), null);
				}
			}
		}
	}
	
	private void replaceTargetClassVariableNameWithThisExpressionInInstanceofExpressions(MethodDeclaration newMethodDeclaration) {
		ExpressionExtractor extractor = new ExpressionExtractor();
		List<Expression> instanceofExpressions = extractor.getInstanceofExpressions(newMethodDeclaration.getBody());
		for(Expression expression : instanceofExpressions) {
			InstanceofExpression instanceofExpression = (InstanceofExpression)expression;
			if(instanceofExpression.getLeftOperand() instanceof SimpleName) {
				SimpleName simpleName = (SimpleName)instanceofExpression.getLeftOperand();
				if(simpleName.getIdentifier().equals(targetClassVariableName)) {
					AST ast = newMethodDeclaration.getAST();
					targetRewriter.set(instanceofExpression, InstanceofExpression.LEFT_OPERAND_PROPERTY, ast.newThisExpression(), null);
				}
			}
			else if(instanceofExpression.getLeftOperand() instanceof FieldAccess) {
				FieldAccess fieldAccess = (FieldAccess)instanceofExpression.getLeftOperand();
				SimpleName simpleName = fieldAccess.getName();
				if(simpleName.getIdentifier().equals(targetClassVariableName)) {
					AST ast = newMethodDeclaration.getAST();
					targetRewriter.set(instanceofExpression, InstanceofExpression.LEFT_OPERAND_PROPERTY, ast.newThisExpression(), null);
				}
			}
		}
	}
	
	private void replaceTargetClassVariableNameWithThisExpressionInAssignments(MethodDeclaration newMethodDeclaration) {
		ExpressionExtractor extractor = new ExpressionExtractor();
		List<Expression> assignments = extractor.getAssignments(newMethodDeclaration.getBody());
		for(Expression expression : assignments) {
			Assignment assignment = (Assignment)expression;
			if(assignment.getLeftHandSide() instanceof SimpleName) {
				SimpleName leftHandSide = (SimpleName)assignment.getLeftHandSide();
				if(leftHandSide.getIdentifier().equals(targetClassVariableName)) {
					AST ast = newMethodDeclaration.getAST();
					targetRewriter.set(assignment, Assignment.LEFT_HAND_SIDE_PROPERTY, ast.newThisExpression(), null);
				}	
			}
			else if(assignment.getLeftHandSide() instanceof FieldAccess) {
				FieldAccess fieldAccess = (FieldAccess)assignment.getLeftHandSide();
				SimpleName leftHandSide = fieldAccess.getName();
				if(leftHandSide.getIdentifier().equals(targetClassVariableName)) {
					AST ast = newMethodDeclaration.getAST();
					targetRewriter.set(assignment, Assignment.LEFT_HAND_SIDE_PROPERTY, ast.newThisExpression(), null);
				}
			}
			if(assignment.getRightHandSide() instanceof SimpleName) {
				SimpleName rightHandSide = (SimpleName)assignment.getRightHandSide();
				if(rightHandSide.getIdentifier().equals(targetClassVariableName)) {
					AST ast = newMethodDeclaration.getAST();
					targetRewriter.set(assignment, Assignment.RIGHT_HAND_SIDE_PROPERTY, ast.newThisExpression(), null);
				}	
			}
			else if(assignment.getRightHandSide() instanceof FieldAccess) {
				FieldAccess fieldAccess = (FieldAccess)assignment.getRightHandSide();
				SimpleName rightHandSide = fieldAccess.getName();
				if(rightHandSide.getIdentifier().equals(targetClassVariableName)) {
					AST ast = newMethodDeclaration.getAST();
					targetRewriter.set(assignment, Assignment.RIGHT_HAND_SIDE_PROPERTY, ast.newThisExpression(), null);
				}
			}
		}
	}
	
	private void replaceThisExpressionWithSourceClassParameterInMethodInvocationArguments(MethodDeclaration newMethodDeclaration) {
		ExpressionExtractor extractor = new ExpressionExtractor();
		List<Expression> methodInvocations = extractor.getMethodInvocations(newMethodDeclaration.getBody());
		for(Expression invocation : methodInvocations) {
			if(invocation instanceof MethodInvocation) {
				MethodInvocation methodInvocation = (MethodInvocation)invocation;
				List<Expression> arguments = methodInvocation.arguments();
				for(Expression argument : arguments) {
					if(argument instanceof ThisExpression) {
						SimpleName parameterName = null;
						if(!additionalArgumentsAddedToMovedMethod.contains("this")) {
							parameterName = addSourceClassParameterToMovedMethod(newMethodDeclaration);
						}
						else {
							AST ast = newMethodDeclaration.getAST();
							String sourceTypeName = sourceTypeDeclaration.getName().getIdentifier();
							parameterName = ast.newSimpleName(sourceTypeName.replaceFirst(Character.toString(sourceTypeName.charAt(0)), Character.toString(Character.toLowerCase(sourceTypeName.charAt(0)))));
						}
						ListRewrite argumentRewrite = targetRewriter.getListRewrite(methodInvocation, MethodInvocation.ARGUMENTS_PROPERTY);
						argumentRewrite.replace(argument, parameterName, null);
					}
				}
			}
		}
	}
	
	private void replaceThisExpressionWithSourceClassParameterInVariableDeclarationInitializers(MethodDeclaration newMethodDeclaration) {
		StatementExtractor extractor = new StatementExtractor();
		List<Statement> variableDeclarations = extractor.getVariableDeclarations(newMethodDeclaration.getBody());
		for(Statement declaration : variableDeclarations) {
			VariableDeclarationStatement variableDeclaration = (VariableDeclarationStatement)declaration;
			List<VariableDeclarationFragment> fragments = variableDeclaration.fragments();
			for(VariableDeclarationFragment fragment : fragments) {
				Expression initializer = fragment.getInitializer();
				if(initializer instanceof ThisExpression) {
					SimpleName parameterName = null;
					if(!additionalArgumentsAddedToMovedMethod.contains("this")) {
						parameterName = addSourceClassParameterToMovedMethod(newMethodDeclaration);
					}
					else {
						AST ast = newMethodDeclaration.getAST();
						String sourceTypeName = sourceTypeDeclaration.getName().getIdentifier();
						parameterName = ast.newSimpleName(sourceTypeName.replaceFirst(Character.toString(sourceTypeName.charAt(0)), Character.toString(Character.toLowerCase(sourceTypeName.charAt(0)))));
					}
					targetRewriter.set(fragment, VariableDeclarationFragment.INITIALIZER_PROPERTY, parameterName, null);
				}
			}
		}
	}
}

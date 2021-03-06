package com.dspot.declex.action;

import static com.helger.jcodemodel.JExpr._new;
import static com.helger.jcodemodel.JExpr._null;
import static com.helger.jcodemodel.JExpr._this;
import static com.helger.jcodemodel.JExpr.assign;
import static com.helger.jcodemodel.JExpr.cast;
import static com.helger.jcodemodel.JExpr.direct;
import static com.helger.jcodemodel.JExpr.invoke;
import static com.helger.jcodemodel.JExpr.ref;

import java.io.BufferedReader;
import java.io.StringReader;
import java.io.StringWriter;
import java.lang.annotation.Annotation;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.annotation.processing.ProcessingEnvironment;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.Name;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.TypeMirror;

import org.androidannotations.AndroidAnnotationsEnvironment;
import org.androidannotations.ElementValidation;
import org.androidannotations.helper.APTCodeModelHelper;
import org.androidannotations.helper.ModelConstants;
import org.androidannotations.holder.EBeanHolder;
import org.androidannotations.holder.EComponentHolder;
import org.androidannotations.internal.process.ProcessHolder.Classes;

import com.dspot.declex.api.action.annotation.Assignable;
import com.dspot.declex.api.action.annotation.Field;
import com.dspot.declex.api.action.annotation.FormattedExpression;
import com.dspot.declex.api.action.annotation.Literal;
import com.dspot.declex.api.action.annotation.StopOn;
import com.dspot.declex.api.action.process.ActionInfo;
import com.dspot.declex.api.action.process.ActionMethod;
import com.dspot.declex.api.action.process.ActionMethodParam;
import com.dspot.declex.api.util.FormatsUtils;
import com.dspot.declex.override.util.DeclexAPTCodeModelHelper;
import com.dspot.declex.share.holder.EnsureImportsHolder;
import com.dspot.declex.util.DeclexConstant;
import com.dspot.declex.util.TypeUtils;
import com.helger.jcodemodel.AbstractJClass;
import com.helger.jcodemodel.IJExpression;
import com.helger.jcodemodel.IJStatement;
import com.helger.jcodemodel.JAnonymousClass;
import com.helger.jcodemodel.JBlock;
import com.helger.jcodemodel.JCatchBlock;
import com.helger.jcodemodel.JClassAlreadyExistsException;
import com.helger.jcodemodel.JCodeModel;
import com.helger.jcodemodel.JConditional;
import com.helger.jcodemodel.JDefinedClass;
import com.helger.jcodemodel.JFormatter;
import com.helger.jcodemodel.JInvocation;
import com.helger.jcodemodel.JMethod;
import com.helger.jcodemodel.JMod;
import com.helger.jcodemodel.JTryBlock;
import com.helger.jcodemodel.JVar;
import com.sun.source.tree.AssignmentTree;
import com.sun.source.tree.BlockTree;
import com.sun.source.tree.CatchTree;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.DoWhileLoopTree;
import com.sun.source.tree.EmptyStatementTree;
import com.sun.source.tree.EnhancedForLoopTree;
import com.sun.source.tree.ExpressionStatementTree;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.ForLoopTree;
import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.IfTree;
import com.sun.source.tree.ImportTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.ModifiersTree;
import com.sun.source.tree.ReturnTree;
import com.sun.source.tree.StatementTree;
import com.sun.source.tree.SwitchTree;
import com.sun.source.tree.SynchronizedTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.Tree.Kind;
import com.sun.source.tree.TreeVisitor;
import com.sun.source.tree.TryTree;
import com.sun.source.tree.VariableTree;
import com.sun.source.tree.WhileLoopTree;
import com.sun.source.util.TreePath;
import com.sun.source.util.TreePathScanner;
import com.sun.source.util.Trees;

public class ActionsProcessor extends TreePathScanner<Boolean, Trees> {
	
	private String debugIndex = "";

	private int actionCount = 0;
	
	private List<MethodInvocationTree> subMethods = new LinkedList<>();
	
	private List<StatementTree> statements = new LinkedList<>();
	
	private JBlock delegatingMethodBody = null;
	private List<JBlock> blocks = new LinkedList<>();
	private List<JBlock> parallelBlock = new LinkedList<>();
	
	private JBlock initialBlock = new JBlock();
	private JAnonymousClass sharedVariablesHolder = null;
	
	private boolean visitingIfCondition;
	private String currentIfCondition;
	private StatementTree elseIfCondition;
	
	private boolean processingTry;
	private boolean visitingTry;
	private TryTree currentTry;
	private boolean visitingCatch;
	
	private boolean visitingVariable;
	private String actionInFieldWithoutInitializer = null;
	
	private String lastMemberIdentifier;
	
	private List<String> currentAction = new LinkedList<>();
	private List<String> currentActionSelectors = new LinkedList<>();
	private List<JInvocation> currentBuildInvocation = new LinkedList<>();
	private List<Map<String, ParamInfo>> currentBuildParams = new LinkedList<>(); 
	
	private Map<Integer, List<BlockDescription>> additionalBlocks = new HashMap<>();
	
	private AndroidAnnotationsEnvironment env;
	private EComponentHolder holder;
	private APTCodeModelHelper codeModelHelper;
	private Element element;
	
	private List<? extends ImportTree> imports;
	
	private boolean ignoreActions;
	private ClassTree anonymousClassTree;
	private boolean processStarted = false;

	private AssignmentTree assignment;
	
	private ElementValidation valid;
	
	//Cache to stored the elements that were already scanned for Actions
	private static Map<Element, Boolean> hasActionMap = new HashMap<>();
	
	public static boolean hasAction(final Element element, AndroidAnnotationsEnvironment env) {
		
		Boolean hasAction = hasActionMap.get(element);
		if (hasAction != null) return hasAction;
		
		final Trees trees = Trees.instance(env.getProcessingEnvironment());
    	final TreePath treePath = trees.getPath(element);
    	
    	//Check if the Action Api was activated for this compilation unit
    	for (ImportTree importTree : treePath.getCompilationUnit().getImports()) {
    		
            if (importTree.getQualifiedIdentifier().toString().startsWith(DeclexConstant.ACTION + ".")) {

            	try {

	            	//Scan first to see if an action exists in the method
	            	TreePathScanner<Boolean, Trees> scanner = new TreePathScanner<Boolean, Trees>() {
	            		@Override
	            		public Boolean visitIdentifier(IdentifierTree id,
	            				Trees trees) {
	            			
	            			String name = id.getName().toString();	            			
	            			if (Actions.getInstance().hasActionNamed(name)) {
	            				//Identifier detected
	            				throw new ActionDetectedException();
	            			}
	            			
	            			return super.visitIdentifier(id, trees);
	            		}
	            	};
	            	scanner.scan(treePath, trees);
                	        		
            	} catch (ActionDetectedException e) {  
            		//This means that an Action identifier was found
            		hasActionMap.put(element, true);
                	return true;
            	}
            	
            	break;
            }
        }
    	
    	hasActionMap.put(element, false);
    	return false;		
	}
	
	public static void validateActions(final Element element, ElementValidation valid, AndroidAnnotationsEnvironment env) {
		
		if (hasAction(element, env)) {
			
    		final Trees trees = Trees.instance(env.getProcessingEnvironment());
        	final TreePath treePath = trees.getPath(element);
        	
			try {
        		ActionsProcessor scanner = new ActionsProcessor(element, null, valid, treePath, env);
            	scanner.scan(treePath, trees);
			} catch (ActionProcessingException e) {
				valid.addError(e.getMessage());
			} catch (IllegalStateException e) {
				valid.addError(e.getMessage());
			}
		}
	}
	
	public static void processActions(final Element element, EComponentHolder holder) {
    	
		if (!hasActionMap.containsKey(element)) {
			throw new RuntimeException("Action not validated: " + element + " in " + holder.getAnnotatedElement());
		}
		
    	if (hasAction(element, holder.getEnvironment())) {
    		
    		final Trees trees = Trees.instance(holder.getEnvironment().getProcessingEnvironment());
        	final TreePath treePath = trees.getPath(element);
        	
        	ActionsProcessor scanner = new ActionsProcessor(element, holder, null, treePath, holder.getEnvironment());
        	scanner.scan(treePath, trees);
    	}
	}
	
	private ActionsProcessor(Element element, EComponentHolder holder, ElementValidation valid, TreePath treePath, AndroidAnnotationsEnvironment env) {
		
		this.element = element;
		this.holder = holder;
		this.env = env;
		this.valid = valid;
		
		this.codeModelHelper = new DeclexAPTCodeModelHelper(env);
		
		pushBlock(initialBlock, null);
		
		imports = treePath.getCompilationUnit().getImports();

		if (showDebugInfo()) System.out.println("PROCESSING: " + holder.getAnnotatedElement());
	}
	
	private boolean isValidating() {
		return valid != null;
	}
	
	private boolean showDebugInfo() {
		if (isValidating()) return false;
		
		if (!env.getProcessingEnvironment().getOptions().containsKey("logLevel")) return false;
			
		String logLevel = env.getProcessingEnvironment().getOptions().get("logLevel");
		if (!logLevel.toLowerCase().trim().equals("debug")) return false;
		
		return true;
	}
	
	private String debugPrefix() {
		String prefix = "";
		return prefix + debugIndex;
	}
	
	private String parseForSpecials(String expression, boolean ignoreThis) {
		if (isValidating()) return expression;
		
		String generatedClass = holder.getGeneratedClass().name();
		String annotatedClass = holder.getAnnotatedElement().getSimpleName().toString();
		
		if (!ignoreThis) {
			expression = expression.replaceAll("(?<![a-zA-Z_$.])this(?![a-zA-Z_$])", generatedClass + ".this");
			expression = expression.replaceAll("(?<![a-zA-Z_$.])super(?![a-zA-Z_$])", generatedClass + ".super");
		}
		
		expression = expression.replaceAll("(?<![a-zA-Z_$.])" + annotatedClass + ".this(?![a-zA-Z_$])", generatedClass + ".this");
		expression = expression.replaceAll("(?<![a-zA-Z_$.])" + annotatedClass + ".super(?![a-zA-Z_$])", generatedClass + ".super");
		
		if (currentAction.get(0) != null) {			
			for (ParamInfo paramInfo : currentBuildParams.get(0).values()) {
				final ActionMethodParam param = paramInfo.param;
				expression = expression.replaceAll("\\" + currentAction.get(0) + "." + param.name + "\\(\\)\\.", "");
			}
		}
		
		return expression;
	}
	
	private void writeVariable(VariableTree variable, JBlock block, IJExpression initializer) {
		
		if (isValidating()) return;
		
		if (showDebugInfo()) System.out.println(debugPrefix() + "writeVariable: " + variable);

		//Inferred variables must start with $ sign
		final String name = variable.getName().toString();
		if (sharedVariablesHolder == null) {
			sharedVariablesHolder = getCodeModel().anonymousClass(Runnable.class);
			JMethod anonymousRunnableRun = sharedVariablesHolder.method(JMod.PUBLIC, getCodeModel().VOID, "run");
			anonymousRunnableRun.annotate(Override.class);
			
			//Add all the created code to the sharedVariablesHolder
			anonymousRunnableRun.body().add(initialBlock);
			
			initialBlock = new JBlock();
			JVar sharedVariablesHolderVar = initialBlock.decl(
					getJClass(Runnable.class.getCanonicalName()), 
					"sharedVariablesHolder", 
					_new(sharedVariablesHolder)
				);
			initialBlock.invoke(sharedVariablesHolderVar, "run");
		}
		
		String variableClass = variableClassFromImports(variable.getType().toString(), true);		
		
		int arrayCounter = 0;
		while (variableClass.endsWith("[]")) {
			arrayCounter++;
			variableClass = variableClass.substring(0, variableClass.length() - 2);
		}
		
		AbstractJClass VARIABLECLASS = getJClass(variableClass);
		for (int i = 0; i < arrayCounter; i++) {
			VARIABLECLASS = VARIABLECLASS.array();
		}
		
		if (!sharedVariablesHolder.containsField(name)) {
			if (initializer != null && !name.startsWith("$")) {
				sharedVariablesHolder.field(
						JMod.NONE, 
						VARIABLECLASS, 
						name
					);
				block.assign(ref(name), initializer);
			} else {
				if (name.startsWith("$")) {
					sharedVariablesHolder.field(
							JMod.NONE, 
							VARIABLECLASS, 
							name
						);
					
					block.assign(ref(name), ref(name.substring(1)));
				} else {
					sharedVariablesHolder.field(
							JMod.NONE, 
							VARIABLECLASS, 
							name
						);												
				}
			}
		} else {
			if (initializer != null) {
				block.assign(ref(name), initializer);
			}
		}
	}
	
	private void writePreviousStatements() {
		
		if (!isValidating()) {
			JBlock block = blocks.get(0);
			
			//Write all the statements till this point
			for (StatementTree statement : statements) {
				if (statement instanceof ExpressionStatementTree) {
					if (showDebugInfo()) System.out.println(debugPrefix() + "writeStatement: " + statement);
					block.directStatement(parseForSpecials(
							statement.toString(), 
							statement instanceof StringExpressionStatement? 
									((StringExpressionStatement)statement).ignoreThis() : false
						));
				}
				
				if (statement instanceof VariableTree) {
					VariableTree variable = (VariableTree) statement;
					IJExpression initializer = variable.getInitializer() == null? 
			                   null : direct(parseForSpecials(variable.getInitializer().toString(), false));
				
					writeVariable(variable, block, initializer);
				}
				
			}			
		}
		
		statements.clear();
	}
	
	private void buildPreviousAction() {
		
		if (isValidating()) return;
		
		//Call the arguments for the last Action
		if (currentAction.get(0) != null) {
			
			if (showDebugInfo()) System.out.println(debugPrefix() + "buildingPreviousAction");
			
			for (String paramName : currentBuildParams.get(0).keySet()) {
				
				final ParamInfo paramInfo = currentBuildParams.get(0).get(paramName);
				
				//The first line of the runnableBlock, is the description line
				if (paramInfo.runnableBlock != null && paramInfo.runnableBlock.getContents().size()==1) {
					currentBuildInvocation.get(0).arg(_null());
				} else {
					currentBuildInvocation.get(0).arg(paramInfo.assignment);
				}
			}
			
		}
	}
	
	@Override
	public Boolean visitVariable(VariableTree variable, Trees trees) {
		//Field action
		if (!(element instanceof ExecutableElement)) {
			if (variable.getInitializer() == null) {
				actionInFieldWithoutInitializer = variable.getType().toString();
				return visitMethodInvocation(null, trees);
			}
		}
		
		if (ignoreActions) return super.visitVariable(variable, trees);
		
		//Ignore variables out of the method
		if (!processStarted) return super.visitVariable(variable, trees);
		
		if (visitingCatch) {
			visitingCatch = false;
			return super.visitVariable(variable, trees);
		}
		
		statements.add(variable);	
		
		visitingVariable = true;
		Boolean result = super.visitVariable(variable, trees);
		addAnonymouseStatements(variable.toString());
		visitingVariable = false;
		
		return result;
	}
	
	@Override
	public Boolean visitAssignment(AssignmentTree assignment, Trees arg1) {
		if (!visitingVariable) this.assignment  = assignment;
		return super.visitAssignment(assignment, arg1);
	}
	
	@Override
	public Boolean visitExpressionStatement(
			ExpressionStatementTree expr, Trees trees) {
		if (ignoreActions) return super.visitExpressionStatement(expr, trees);
		
		statements.add(expr);
		
		assignment = null;
		Boolean result = super.visitExpressionStatement(expr, trees);
		addAnonymouseStatements(expr.toString());
		
		return result;
	}
	
	@Override
	public Boolean visitReturn(ReturnTree returnTree, Trees trees) {
		if (ignoreActions) return super.visitReturn(returnTree, trees);
		
		statements.add(new StringExpressionStatement(returnTree.toString()));
		
		Boolean result = super.visitReturn(returnTree, trees);
		addAnonymouseStatements(returnTree.toString());
		
		return result;
	}
	
	@Override
	public Boolean visitIdentifier(IdentifierTree id,
			Trees trees) {
		
		//This will happen with identifiers prior to the main block (ex. Annotations)
		if (!processStarted) return true;
				
		final String idName = id.toString();
		if (visitingIfCondition) {
			
			if (idName.equals(currentAction.get(0))) {
						
				if (!currentIfCondition.equals("(" + idName + "." + lastMemberIdentifier + ")")) {
					throw new ActionProcessingException(
							"Malformed Action Event Selector: if" + currentIfCondition
						);
				}
				
				if (elseIfCondition != null) {
					throw new ActionProcessingException(
							"Else Block not supported for Action Events: if" + currentIfCondition 
							+ " ...\nelse " + elseIfCondition
						);
				}
	
				for (ParamInfo paramInfo : currentBuildParams.get(0).values()) {
					final ActionMethodParam param = paramInfo.param;
										
					if (param.name.equals(lastMemberIdentifier)) {
						
						currentActionSelectors.add(0, idName + "." + lastMemberIdentifier + "()");

						//If the block already exists, do not create a new Runnable 
						if (paramInfo.runnableBlock != null) {
							pushBlock(paramInfo.runnableBlock, null);
						} else {
							
							JDefinedClass anonymousRunnable = getCodeModel().anonymousClass((AbstractJClass) param.clazz);
							JMethod anonymousRunnableRun = anonymousRunnable.method(JMod.PUBLIC, getCodeModel().VOID, "run");
							anonymousRunnableRun.annotate(Override.class);
							anonymousRunnableRun.body().directStatement("//ACTION EVENT: " + param.name);
							
							ParamInfo newParamInfo = new ParamInfo(
									paramInfo.param, 
									anonymousRunnableRun.body(), 
									_new(anonymousRunnable)
								);
							currentBuildParams.get(0).put(param.name, newParamInfo);
							
							JBlock anonymousBlock = checkTryForBlock(anonymousRunnableRun.body(), false);
							pushBlock(anonymousBlock, null);
						}
						
						break;            						
					}
				}

				if (showDebugInfo()) {
					System.out.println(debugPrefix() + "newEvent: " + currentIfCondition);
					debugIndex = debugIndex + "    ";
				}
				
				currentIfCondition = null;
			} else {
				
				if (Actions.getInstance().hasActionNamed(idName)) {
					System.out.println("LL: " + currentIfCondition);
					if (parseForSpecials(currentIfCondition, false).contains(idName)) {
						throw new ActionProcessingException(
								"Action Selector out of context: " + idName + " in if" + currentIfCondition 
							);
						
					}					
				}
				
			}
		} 
		
		//Accessing Action from within unsupported structure
		if (Actions.getInstance().hasActionNamed(idName) && ignoreActions) {
			throw new ActionProcessingException(
					"Action is not supported in this location: " + idName
				);
		}
		
		subMethods.clear();		
			
		//Ensure import of all the identifiers
		variableClassFromImports(idName, true);
		
		return super.visitIdentifier(id, trees);
	}

	@Override
	public Boolean visitEmptyStatement(EmptyStatementTree arg0, Trees trees) {
		if (ignoreActions) return super.visitEmptyStatement(arg0, trees);
		
		if (showDebugInfo()) System.out.println(debugPrefix() + "empty");
		return super.visitEmptyStatement(arg0, trees);
	}
	
	@Override
	public Boolean visitMemberSelect(MemberSelectTree member,
			Trees trees) { 
		if (ignoreActions) return super.visitMemberSelect(member, trees);
		
		lastMemberIdentifier = member.getIdentifier().toString();
		return super.visitMemberSelect(member, trees);
	}
	
	@Override
	public Boolean visitIf(IfTree ifTree, Trees trees) {
		if (ignoreActions) return super.visitIf(ifTree, trees);
		
		if (!ifTree.getThenStatement().getKind().equals(Kind.BLOCK)
				|| (elseIfCondition != null && !elseIfCondition.getKind().equals(Kind.BLOCK))) {
			//Run this kind of Ifs without Action processing
			writeDirectStructure(ifTree.toString());
			
			ignoreActions = true;
			Boolean result = super.visitIf(ifTree, trees);
			ignoreActions = false;
			
			return result;
		}
		
		visitingIfCondition = true;
		currentIfCondition = ifTree.getCondition().toString();
		elseIfCondition = ifTree.getElseStatement();
		
		writePreviousStatements();    					
		
		return super.visitIf(ifTree, trees);
	}
	
	@Override
	public Boolean visitMethodInvocation(MethodInvocationTree invoke,
			Trees trees) {
		if (ignoreActions) return super.visitMethodInvocation(invoke, trees);
		if (visitingIfCondition) return super.visitMethodInvocation(invoke, trees);

		String methodSelect = invoke != null? invoke.getMethodSelect().toString() 
				                            : actionInFieldWithoutInitializer;
	
		if (methodSelect.contains(".")) {
			subMethods.add(invoke);
		} else {
			
			if (Actions.getInstance().hasActionNamed(methodSelect)) {
				
				String actionClass = Actions.getInstance().getActionNames().get(methodSelect);
				ActionInfo actionInfo = Actions.getInstance().getActionInfos().get(actionClass);

				if (delegatingMethodBody == null) {
					if (isValidating()) {
						//This block is not going to be used
						delegatingMethodBody = new JBlock();
						
						if (!(element instanceof ExecutableElement)) { 
							
							///Current Action Information for the new Block
							currentAction.add(0, null);
							currentBuildInvocation.add(0, null);
							currentBuildParams.add(0, new LinkedHashMap<String, ParamInfo>());
							processStarted = true;
							
						}
					} else {
						if (element instanceof ExecutableElement) {
							//Methods
							JMethod delegatingMethod = codeModelHelper.overrideAnnotatedMethod((ExecutableElement) element, holder);
							codeModelHelper.removeBody(delegatingMethod);
							delegatingMethodBody = delegatingMethod.body();
						} else {
							//Fields
							JDefinedClass anonymous = getCodeModel().anonymousClass(
									getJClass(DeclexConstant.ACTION + "." + methodSelect)
								);
								
							JMethod fire = anonymous.method(JMod.PUBLIC, getCodeModel().VOID, "fire");
							fire.annotate(Override.class);
							delegatingMethodBody = fire.body();
							
							holder.getInitBody().assign(ref(element.getSimpleName().toString()), _new(anonymous));
							
							///Current Action Information for the new Block
							currentAction.add(0, null);
							currentBuildInvocation.add(0, null);
							currentBuildParams.add(0, new LinkedHashMap<String, ParamInfo>());
							processStarted = true;
							
							if (showDebugInfo()) {
								System.out.println(debugPrefix() + "FieldStart:" + subMethods);
								debugIndex = debugIndex + "    ";				
							}
						}
					}
				}

				String actionName = methodSelect.substring(0, 1).toLowerCase() 
		                  + methodSelect.substring(1) + actionCount;
				if (actionInfo.isGlobal) {
					actionName = actionName + "$" + element.getSimpleName();
				}
				
				JBlock block = blocks.get(0);
				
				//This is important to detect empty blocks
				block.directStatement("//===========ACTION: " + actionName + "===========");
				
				buildPreviousAction();
				
				VariableTree variable = null;
				if (visitingVariable) {
					variable = (VariableTree) statements.get(statements.size()-1);
				}
				
				//Remove last statement (represents this Action)
				if (statements.size() > 0) {
					statements.remove(statements.size()-1);
				}
				
				writePreviousStatements();
				            					
				currentAction.set(0, methodSelect);
				actionCount++;
				
				IJExpression context = holder == null? ref("none") : holder.getContextRef();
				if (context == _this()) {
					context = holder.getGeneratedClass().staticRef("this");
				}
				
				AbstractJClass injectedClass = getJClass(actionClass + ModelConstants.generationSuffix());
				
				final JVar action;
				if (actionInfo.isGlobal && !isValidating()) {
					action = holder.getGeneratedClass().field(JMod.PRIVATE, injectedClass, actionName);
					block.assign(action, injectedClass.staticInvoke(EBeanHolder.GET_INSTANCE_METHOD_NAME).arg(context));
				} else {
					action = block.decl(
							JMod.FINAL,
							injectedClass, 
							actionName,
							injectedClass.staticInvoke(EBeanHolder.GET_INSTANCE_METHOD_NAME).arg(context)
					);
				}
				
								
				
				actionInfo.clearMetaData();
				
				JBlock preInit = block.blockVirtual();
				JInvocation initInvocation = block.invoke(action, "init");
				if (invoke != null) {
					
					for (String arg : processArguments("init", invoke, action, actionInfo)) {
						initInvocation.arg(direct(arg));
					}
				} 
				JBlock postInit = block.blockVirtual();
				
				Collections.reverse(subMethods);
				JInvocation externalInvoke = null;
				
				String[] stopOn = null;
				boolean buildAndExecute = true;
				
				for (MethodInvocationTree invocation : subMethods) {
					String name = invocation.getMethodSelect().toString();
					int index = name.lastIndexOf('.');
					name = name.substring(index+1);
					
					if (externalInvoke == null) {
						List<ActionMethod> methods = actionInfo.methods.get(name);
						if (methods != null && methods.size() > 0) {
							if (!methods.get(0).resultClass.equals(actionInfo.holderClass)) {
								externalInvoke = invoke(action, name);					
								
								if (methods.get(0).annotations != null) {
									for (Annotation annotation : methods.get(0).annotations) {	
										if (annotation instanceof StopOn) {
											stopOn = ((StopOn) annotation).value();
										}
									}
								}
							}
						}

						JInvocation subMethodInvocation = externalInvoke==null? block.invoke(action, name) : externalInvoke;
						for (String arg : processArguments(name, invocation, action, actionInfo)) {
							subMethodInvocation = subMethodInvocation.arg(direct(arg));
	    				}
					} else {
						externalInvoke = externalInvoke.invoke(name);
						for (ExpressionTree arg : invocation.getArguments()) {
							externalInvoke = externalInvoke.arg(direct(arg.toString()));
	    				}
						
						if (stopOn != null) {
							for (String stopOnMethodName : stopOn) {
								if (stopOnMethodName.equals(name)) {
									buildAndExecute = false;
									break;
								}
							}
						}
					}
					
				}
					
				List<ActionMethod> buildMethods = actionInfo.methods.get("build");
				if (buildMethods != null && buildMethods.size() > 0) {
					
					try {
						actionInfo.metaData.put("action", action);						
						
						if (isValidating()) {
							actionInfo.validateProcessors();
						} else {
							actionInfo.metaData.put("holder", holder);
							actionInfo.callProcessors();
						}						
						
					} catch (IllegalStateException e) {
						throw new ActionProcessingException(
									e.getMessage()
								);
					}
					
					@SuppressWarnings("unchecked")
					List<IJStatement> preInitBlocks = (List<IJStatement>) actionInfo.metaData.get("preInitBlocks");
					if (preInitBlocks != null) {
						for (IJStatement preInitBlock : preInitBlocks) {
							preInit.add(preInitBlock);
						}
					}
					
					@SuppressWarnings("unchecked")
					List<IJStatement> postInitBlocks = (List<IJStatement>) actionInfo.metaData.get("postInitBlocks");
					if (postInitBlocks != null) {
						for (IJStatement postBuildBlock : postInitBlocks) {
							postInit.add(postBuildBlock);
						}
					}
					
					if (buildAndExecute) {
						
						@SuppressWarnings("unchecked")
						List<IJStatement> preBuildBlocks = (List<IJStatement>) actionInfo.metaData.get("preBuildBlocks");
						if (preBuildBlocks != null) {
							for (IJStatement preBuildBlock : preBuildBlocks) {
								block.add(preBuildBlock);
							}
						}
						
						currentBuildInvocation.set(0, block.invoke(action, "build"));
						currentBuildParams.get(0).clear();
						
						@SuppressWarnings("unchecked")
						List<IJStatement> postBuildBlocks = (List<IJStatement>) actionInfo.metaData.get("postBuildBlocks");
						if (postBuildBlocks != null) {
							for (IJStatement postBuildBlock : postBuildBlocks) {
								block.add(postBuildBlock);
							}
						}
						
						if (externalInvoke != null) {
							externalInvokeInBlock(block, externalInvoke, variable);
						}
						
						ActionMethod buildMethod = buildMethods.get(0);
						boolean firstParam = true;
						for (ActionMethodParam param : buildMethod.params) {
							ParamInfo paramInfo;
							if (firstParam) {
								
								JDefinedClass anonymousRunnable = getCodeModel().anonymousClass((AbstractJClass) param.clazz);
								JMethod anonymousRunnableRun = anonymousRunnable.method(JMod.PUBLIC, getCodeModel().VOID, "run");
								anonymousRunnableRun.annotate(Override.class);
								anonymousRunnableRun.body().directStatement("//ACTION EVENT: " + param.name);
								
								paramInfo = new ParamInfo(param, anonymousRunnableRun.body(), _new(anonymousRunnable));
								
								JBlock anonymousBlock = checkTryForBlock(anonymousRunnableRun.body(), false);
								blocks.set(0, anonymousBlock);
								
								if (showDebugInfo()) {
									if (invoke != null) {
										System.out.println(debugPrefix() + "writeAction: " + invoke);
									} else {
										System.out.println(debugPrefix() + "writeAction: " + methodSelect);
									}
									
								}
								
								firstParam = false;
								
							} else {
								paramInfo = new ParamInfo(param, null, _null());
							}
							
							currentBuildParams.get(0).put(param.name, paramInfo);
						}
					
						block.invoke(action, "execute");
					} else {
						currentBuildInvocation.set(0, null);
						currentBuildParams.get(0).clear();
						
						if (externalInvoke != null) {
							externalInvokeInBlock(block, externalInvoke, variable);
						}
					}
				}
			
				block.directStatement("//============================================");
				
				if (!(element instanceof ExecutableElement)) {
					finishBlock();
					return true;
				}
			}
			
			subMethods.clear();
		}
		
		return super.visitMethodInvocation(invoke, trees);
	}
	
	private void externalInvokeInBlock(JBlock block, JInvocation invocation, VariableTree variable) {
		if (visitingVariable) {							
			writeVariable(variable, block, invocation);
		} else {
			
			if (assignment != null) {
				block.assign(ref(assignment.getVariable().toString()), invocation);
				assignment = null;
			} else {
				block.add(invocation);
			}
			
		}
	}
	    		
	@Override
	public Boolean visitBlock(BlockTree blockTree, Trees tree) {
		if (ignoreActions) return super.visitBlock(blockTree, tree);
		
		boolean isParallelBlock = true;
		
		if (visitingIfCondition) {

			if (currentIfCondition != null) {

				JBlock block = blocks.get(0);
				JConditional cond = block._if(direct(parseForSpecials(currentIfCondition, false)));
				
				if (elseIfCondition != null) {
					addAdditionalBlock(cond._else(), "elseBlock:");					
				}				
				
				pushBlock(cond._then(), "newIf: " + currentIfCondition);
			}

			visitingIfCondition = false;
			isParallelBlock = false;
		} 
		
		if (processingTry) {
			JBlock block = blocks.get(0);
			
			//Create the runnables for the Try
			BlockTree finallyBlock = currentTry.getFinallyBlock();
			if (finallyBlock != null) {
				JDefinedClass anonymousRunnable = getCodeModel().anonymousClass(Runnable.class);
				JMethod anonymousRunnableRun = anonymousRunnable.method(JMod.PUBLIC, getCodeModel().VOID, "run");
				anonymousRunnableRun.annotate(Override.class);
				JBlock runnableBlock = anonymousRunnableRun.body();
				
				block.decl(
						JMod.FINAL, 
						getJClass(Runnable.class.getCanonicalName()), 
						"tryFinallyRunnable",
						_new(anonymousRunnable)
					);
				addAdditionalBlock(runnableBlock, "finallyBlock:");
			}
			
			List<CatchTree> catches = new ArrayList<>(currentTry.getCatches());
			Collections.reverse(catches);
			
			for (CatchTree catchTree : catches) {
				String className = catchTree.getParameter().getType().toString();
				if (className.contains(".")) {
					className = className.substring(className.indexOf('.') + 1);
				}
				
				String variableClass = variableClassFromImports(catchTree.getParameter().getType().toString());				
				final AbstractJClass catchClass = getJClass(variableClass);
				final String catchName = catchTree.getParameter().getName().toString();
				
				if (!isValidating()) {
					try {
						JDefinedClass onErrorClass = holder.getGeneratedClass()._class(JMod.ABSTRACT | JMod.STATIC, className + "OnError");					
						JMethod onErrorMethod = onErrorClass.method(JMod.PUBLIC | JMod.ABSTRACT, getCodeModel().VOID, "onError");
						onErrorMethod.param(catchClass, catchName);
					} catch (JClassAlreadyExistsException e) {}					
				}
				
				AbstractJClass onErrorClass = getJClass(className + "OnError");
				
				JDefinedClass anonymousOnError = getCodeModel().anonymousClass(onErrorClass);
				JMethod anonymousOnErrorMethod = anonymousOnError.method(JMod.PUBLIC, getCodeModel().VOID, "onError");
				anonymousOnErrorMethod.annotate(Override.class);
				anonymousOnErrorMethod.param(JMod.FINAL, catchClass, catchName);
				JBlock runnableBlock = anonymousOnErrorMethod.body();
				
				block.decl(
						JMod.FINAL, 
						onErrorClass, 
						"catch" + className + "OnError",
						_new(anonymousOnError)
					);
				addAdditionalBlock(runnableBlock, "catchBlock: " + catchTree.getParameter());
			}
			
			JBlock tryBlock = checkTryForBlock(block, true);
			pushBlock(tryBlock, "newTry: " + currentIfCondition);
			
			isParallelBlock = false;
		}
	
		if (!processStarted) {
			if (showDebugInfo()) {
				System.out.println(debugPrefix() + "MethodStart:");
				debugIndex = debugIndex + "    ";				
			} 
			isParallelBlock = false;
		}
		
		//Current Action Information for the new Block
		currentAction.add(0, null);
		currentBuildInvocation.add(0, null);
		currentBuildParams.add(0, new LinkedHashMap<String, ParamInfo>());

		if (hasAdditionalBlock()) {
			pushAdditionalBlock();
			isParallelBlock = false;
		}
		
		//Used for execution of actions in parallel
		if (isParallelBlock) {			
			writePreviousStatements();
			
			JBlock block = parallelBlock.get(0);			
			pushBlock(block.block(), "newBlock: ");
		}
		
		processStarted = true;
		
		Boolean result = super.visitBlock(blockTree, tree); 
		finishBlock();
		return result;
	}
	
	private boolean hasAdditionalBlock() {
		List<BlockDescription> descriptions = additionalBlocks.get(blocks.size());
		return descriptions != null;
	}
	
	private void addAdditionalBlock(JBlock block, String blockName) {
		BlockDescription blockDescription = new BlockDescription(block, blockName);
		
		List<BlockDescription> descriptions = additionalBlocks.get(blocks.size());
		if (descriptions == null) {
			descriptions = new LinkedList<>();
			additionalBlocks.put(blocks.size(), descriptions);
		}
		
		descriptions.add(blockDescription);
	}
	
	private void removeAdditionalBlock() {
		List<BlockDescription> descriptions = additionalBlocks.get(blocks.size());
		if (descriptions != null) {
			descriptions.remove(descriptions.size()-1);
			
			if (descriptions.size() == 0) {
				additionalBlocks.remove(blocks.size());
			}
		}
	}
	
	private void pushAdditionalBlock() {
		List<BlockDescription> descriptions = additionalBlocks.get(blocks.size());
		if (descriptions != null) {
			BlockDescription blockDescription = descriptions.get(descriptions.size()-1);
			removeAdditionalBlock();
			pushBlock(blockDescription.block, blockDescription.description);
		}
		
	}
	
	private void pushBlock(JBlock block, String blockName) {
		blocks.add(0, block);
		parallelBlock.add(0, block);
		
		if (showDebugInfo() && blockName != null) {
			System.out.println(debugPrefix() + blockName);
			debugIndex = debugIndex + "    ";	
		}
	}
	
	private void popBlock() {
		if (showDebugInfo()) {
			debugIndex = debugIndex.substring(0, debugIndex.length()-4);
			System.out.println(debugPrefix() + "end");
		}		
		
		blocks.remove(0);
		parallelBlock.remove(0);
	}
	
	private void finishBlock() {
		writePreviousStatements();						
		buildPreviousAction();
		
		currentAction.remove(0);
		currentBuildInvocation.remove(0);
		currentBuildParams.remove(0);
		
		popBlock();
		
		processingTry = false;
		
		//If the method concluded, populate it
		if (currentAction.size() == 0) {
			delegatingMethodBody.add(initialBlock);
		}	
	}

	private List<ExpressionStatementTree> statementsFromCode(String code) {
		List<ExpressionStatementTree> statements = new LinkedList<>();
		
		//Write line by line to format better the text
		try {
			BufferedReader bufReader = new BufferedReader(new StringReader(code));
			
			String line = null;
			while((line=bufReader.readLine()) != null) {
				statements.add(new StringExpressionStatement(line));
			}
		} catch (Exception e) {}
		
		return statements;
	}
	
	private void writeDirectStructure(String code) {
		statements.addAll(statementsFromCode(code));		
	}
	
	@Override
	public Boolean visitTry(TryTree tryTree, Trees trees) {
		
		if (ignoreActions) return super.visitTry(tryTree, trees);
			
		if (visitingTry) {
			throw new ActionProcessingException(
					"Nested try are not supported: " + tryTree
				);
		}
		
		writePreviousStatements();
		
		currentTry = tryTree;
		processingTry = true;
		
		visitingTry = true;		
		Boolean result = super.visitTry(tryTree, trees);
		visitingTry = false;
		
		return result;
	}
	
	@Override
	public Boolean visitCatch(CatchTree catchTree, Trees trees) {
		visitingCatch = true;
		Boolean result = super.visitCatch(catchTree, trees);
		visitingCatch = false;
		return result;
	}
	
	private JBlock checkTryForBlock(JBlock block, boolean firstTry) {
		
		if (processingTry) {
			JTryBlock tryBlock = block._try();
			
			BlockTree finallyBlock = currentTry.getFinallyBlock();
			if (finallyBlock != null && firstTry) {
				tryBlock._finally().invoke(ref("tryFinallyRunnable"), "run");
			}
			
			if (currentTry.getCatches() != null && currentTry.getCatches().size() > 0) {
				JCatchBlock catchBlock = tryBlock._catch(getClasses().THROWABLE);
				JVar e = catchBlock.param("e");
				
				JBlock conditions = catchBlock.body();
				for (CatchTree catchTree : currentTry.getCatches()) {
					String className = catchTree.getParameter().getType().toString();
					if (className.contains(".")) {
						className = className.substring(className.indexOf('.') + 1);
					}
					
					String variableClass = variableClassFromImports(catchTree.getParameter().getType().toString());

					//It is used conditionals here to catch the errors, cause' the try-catch
					//can be placed in a block from where a specific exception is not thrown,
					//and java will not compile in that situation (this is why conditionals and not
					//catch statements are used).
					JConditional ifInstance = conditions._if(e._instanceof(getJClass(variableClass)));
					ifInstance._then().invoke(ref("catch" + className + "OnError"), "onError")
					                  .arg(cast(getJClass(variableClass), e));
					
					conditions = ifInstance._else();
				}
				
				//TODO if the error is not handled, it should be thrown someway
				conditions.directStatement("//TODO Throw the exception");
				conditions.directStatement("//throw e;");
				//conditions._throw(e);
			}
			
			return tryBlock.body();
		}
		
		return block;
	}
	
	@Override
	public Boolean visitSwitch(SwitchTree switchTree, Trees trees) {
		if (ignoreActions) return super.visitSwitch(switchTree, trees);
				
		writeDirectStructure(switchTree.toString());
		
		ignoreActions = true;
		Boolean result = super.visitSwitch(switchTree, trees);
		ignoreActions = false;
		
		return result;
	}
	
	@Override
	public Boolean visitForLoop(ForLoopTree forLoop, Trees trees) {
		if (ignoreActions) return super.visitForLoop(forLoop, trees);
				
		writeDirectStructure(forLoop.toString());
		
		ignoreActions = true;
		Boolean result = super.visitForLoop(forLoop, trees);
		ignoreActions = false;
		
		return result;
	}
	
	@Override
	public Boolean visitEnhancedForLoop(EnhancedForLoopTree forLoop, Trees trees) {
		if (ignoreActions) return super.visitEnhancedForLoop(forLoop, trees);
				
		writeDirectStructure(forLoop.toString());
		
		ignoreActions = true;
		Boolean result = super.visitEnhancedForLoop(forLoop, trees);
		ignoreActions = false;
		
		return result;
	}
	
	@Override
	public Boolean visitWhileLoop(WhileLoopTree whileLoop, Trees trees) {
		if (ignoreActions) return super.visitWhileLoop(whileLoop, trees);
				
		writeDirectStructure(whileLoop.toString());
		
		ignoreActions = true;
		Boolean result = super.visitWhileLoop(whileLoop, trees);
		ignoreActions = false;
		
		return result;
	}
	
	@Override
	public Boolean visitDoWhileLoop(DoWhileLoopTree doWhileLoop, Trees trees) {
		if (ignoreActions) return super.visitDoWhileLoop(doWhileLoop, trees);
				
		writeDirectStructure(doWhileLoop.toString());
		
		ignoreActions = true;
		Boolean result = super.visitDoWhileLoop(doWhileLoop, trees);
		ignoreActions = false;
		
		return result;
	}
	
	@Override
	public Boolean visitClass(ClassTree cls, Trees trees) {
		if (ignoreActions) return super.visitClass(cls, trees); 
				
		ignoreActions = true;
		anonymousClassTree = cls;
		Boolean result = super.visitClass(cls, trees);
		ignoreActions = false;
		
		return result;
	}
	
	public void addAnonymouseStatements(final String expression) {
		
		if (anonymousClassTree != null) {
			StatementTree lastStatement = statements.get(statements.size()-1);
			statements.remove(statements.size()-1);
						
			int anonymousStart = expression.indexOf("new " + anonymousClassTree.getSimpleName());
			String start = expression.substring(0, anonymousStart);
			
			if (lastStatement instanceof VariableTree) {
				statements.add(new VariableExpressionWithoutInitializer((VariableTree) lastStatement));
				
				Matcher matcher = Pattern.compile(((VariableTree) lastStatement).getName() + "\\s*=").matcher(expression);
				if (matcher.find()) {
					start = start.substring(matcher.start());
				}
			}
			
			int anonymousEnd = 0;
			String end = null;
			
			int curlyBracketCount = 0;
			for (int i = anonymousStart; i < expression.length(); i++) {
				if (expression.charAt(i) == '{') curlyBracketCount++;
				
				if (expression.charAt(i) == '}') {
					curlyBracketCount--;
					
					if (curlyBracketCount == 0) {
						anonymousEnd = i+1;
						end = expression.substring(anonymousEnd);
						break;
					}
				}				
			}
			
			if (!start.equals("")) {
				statements.add(new StringExpressionStatement(start));
			}
			
			List<ExpressionStatementTree> anonymouseStatements = 
					statementsFromCode(expression.substring(anonymousStart, anonymousEnd));
			for (ExpressionStatementTree statement : anonymouseStatements) {
				((StringExpressionStatement)statement).setIgnoreThis();
			}
			
			statements.addAll(anonymouseStatements);
			
			if (end.equals("") && !expression.endsWith(";")) {
				end = ";";
			}
			
			if (!end.equals("")) {
				statements.add(new StringExpressionStatement(end));
			}
			
			anonymousClassTree = null;
		}
	}
	
	@Override
	public Boolean visitSynchronized(SynchronizedTree sync, Trees trees) {
		if (ignoreActions) return super.visitSynchronized(sync, trees);
				
		writeDirectStructure(sync.toString());
		
		ignoreActions = true;
		Boolean result = super.visitSynchronized(sync, trees);
		ignoreActions = false;
		
		return result;
	}
	
	private List<String> processArguments(String methodName, MethodInvocationTree invocation,  JVar action, ActionInfo actionInfo) {
		Pattern patternForStringLiterals = Pattern.compile("\"((?:\\\\\"|[^\"])*?)\"");
		
		List<String> arguments = new LinkedList<>();
		boolean matchFound = false;
		
		List<ActionMethod> methods = actionInfo.methods.get(methodName);
		if (methods == null) {
			throw new ActionProcessingException(
					"Method \"" + methodName + "\" not found for action " + invocation
				);
		}		
		
		for (ActionMethod method : methods) {
			
			List<ActionMethodParam> params = method.params;
			
			if (invocation.getArguments().size() == params.size()) {
				
				method.metaData = new HashMap<>();
				
				for (int i = 0; i < params.size(); i++) {
					final ActionMethodParam param = params.get(i);
					
					arguments.clear();
					param.metaData = new HashMap<>();

					String currentParam = invocation.getArguments().get(i).toString();
					param.metaData.put("value", currentParam);
					
					boolean useArguments = false;
					for (Annotation annotation : param.annotations) {					
						
						//Literal Expressions
						if (annotation instanceof Literal) {
							boolean finded = false;			
							Matcher matcher = patternForStringLiterals.matcher(currentParam);
							
							while (matcher.find()) {
								finded = true;
								
								String matched = matcher.group(0);
								if (!matched.equals(currentParam)) {
									throw new ActionProcessingException("You should provide a literal value for \"fields\" in action " + invocation);
								}
								
								String literalStringValue = matcher.group(1);
								
								IJExpression exp = FormatsUtils.expressionFromString(literalStringValue);
								param.metaData.put("literalExpression", exp);
								param.metaData.put("literal", literalStringValue);
							}

							if (!finded) {
								throw new ActionProcessingException("You should provide a literal value for \"fields in action invocation");
							}
						}
						
						//Formatted Expressions
						if (annotation instanceof FormattedExpression) {

							boolean finded = false;			
							Matcher matcher = patternForStringLiterals.matcher(currentParam);
							
							while (matcher.find()) {
								finded = true;
								
								String matched = matcher.group(0);
								String literalStringValue = matcher.group(1);
								
								IJExpression exp = FormatsUtils.expressionFromString(literalStringValue);
								
								currentParam = currentParam.replace(matched, expressionToString(exp));						
							}

							if (finded) {
								arguments.add(currentParam);
								param.metaData.put("value", currentParam);								
								currentParam = null;
								useArguments = true;								
							}

						} 
						
						else if (annotation instanceof Assignable) {
							@SuppressWarnings("unchecked")
							List<IJStatement> postBuildBlocks = (List<IJStatement>) actionInfo.metaData.get("postBuildBlocks");
							if (postBuildBlocks == null) {
								postBuildBlocks = new LinkedList<>();
								actionInfo.metaData.put("postBuildBlocks", postBuildBlocks);
							}
							
							postBuildBlocks.add(assign(ref(currentParam), action.invoke(((Assignable)annotation).value())));
						}
						
						else if (annotation instanceof Field) {						
							
							Element fieldElement = findField(element.getEnclosingElement(), currentParam);
							
							if (fieldElement != null) {
								param.metaData.put("field", fieldElement);
								param.metaData.put("fieldName", fieldElement.getSimpleName().toString());
								
								String fieldClass = TypeUtils.typeFromTypeString(fieldElement.asType().toString(), env);
								param.metaData.put("fieldClass", fieldClass);
								param.metaData.put("fieldJClass", getJClass(fieldClass));
							} else {
								throw new ActionProcessingException(
										"There's no an accesible field named: " + currentParam + " in " + invocation
									);
							}
						}
						
						if (currentParam != null) arguments.add(currentParam);
					}
					
					if (useArguments) return arguments;
				}
			} 
		}
		
		if (!matchFound) {
			arguments.clear();
			for (ExpressionTree arg : invocation.getArguments()) {
				arguments.add(arg.toString());
			}
		}
		
		return arguments;
	}
	
	private Element findField(Element element, String fieldName) {
		
		List<? extends Element> elems = element.getEnclosedElements();
		for (Element elem : elems) {
			if (elem.getKind() == ElementKind.FIELD) {
				if (elem.getModifiers().contains(Modifier.PRIVATE)) continue;
				
				if (elem.getSimpleName().toString().equals(fieldName)) {
					return elem;
				}
			}
		}
		
		final ProcessingEnvironment env = this.env.getProcessingEnvironment();
		
		//Apply to Extensions
		List<? extends TypeMirror> superTypes = env.getTypeUtils().directSupertypes(element.asType());
		for (TypeMirror type : superTypes) {
			TypeElement superElement = env.getElementUtils().getTypeElement(type.toString());
			
			Element elem = findField(superElement, fieldName);
			if (elem != null) return elem;
		}
		
		return null;

	}
	
	private String expressionToString(IJExpression expression) {
	    if (expression == null) {
	        throw new IllegalArgumentException("Generable must not be null.");
	    }
	    final StringWriter stringWriter = new StringWriter();
	    final JFormatter formatter = new JFormatter(stringWriter);
	    expression.generate(formatter);
	    
	    return stringWriter.toString();
	}
	
	private String variableClassFromImports(final String variableClass) {
		return this.variableClassFromImports(variableClass, false);
	}
	
	private String variableClassFromImports(final String variableClass, boolean ensureImport) {
		
		for (ImportTree importTree : imports) {
			String lastElementImport = importTree.getQualifiedIdentifier().toString();
			String firstElementName = variableClass;
			String currentVariableClass = "";
			
			int pointIndex = lastElementImport.lastIndexOf('.');
			if (pointIndex != -1) {
				lastElementImport = lastElementImport.substring(pointIndex + 1);
			}
			
			pointIndex = firstElementName.indexOf('.');
			if (pointIndex != -1) {
				firstElementName = firstElementName.substring(0, pointIndex);
				currentVariableClass = variableClass.substring(pointIndex);
			}
			
			while (firstElementName.endsWith("[]")) {
				firstElementName = firstElementName.substring(0, firstElementName.length()-2);
				if (currentVariableClass.isEmpty()) currentVariableClass = currentVariableClass + "[]";
			}
			
			if (lastElementImport.equals(firstElementName)) {
				
				if (!isValidating() && ensureImport && !importTree.isStatic()) {
					EnsureImportsHolder importsHolder = holder.getPluginHolder(new EnsureImportsHolder(holder));
					importsHolder.ensureImport(importTree.getQualifiedIdentifier().toString());	
				}
				
				return importTree.getQualifiedIdentifier() + currentVariableClass;
			}
		}
		
		return variableClass;
	}
		
	private AbstractJClass getJClass(String clazz) {
		return env.getJClass(clazz);
	}
		
	private JCodeModel getCodeModel() {
		return env.getCodeModel();
	}
	
	private Classes getClasses() {
		return env.getClasses();
	}

	private class VariableExpressionWithoutInitializer implements VariableTree {

		VariableTree variableTree;
		
		public VariableExpressionWithoutInitializer(VariableTree variableTree) {
			this.variableTree = variableTree;
		}
		
		@Override
		public <R, D> R accept(TreeVisitor<R, D> arg0, D arg1) {
			return variableTree.accept(arg0, arg1);
		}

		@Override
		public Kind getKind() {
			return variableTree.getKind();
		}

		@Override
		public ExpressionTree getInitializer() {
			return null;
		}

		@Override
		public ModifiersTree getModifiers() {
			return variableTree.getModifiers();
		}

		@Override
		public Name getName() {
			return variableTree.getName();
		}

		@Override
		public Tree getType() {
			return variableTree.getType();
		}
		
	}
	
	private class StringExpressionStatement implements ExpressionStatementTree {

		String statement;
		boolean ignoreThis;
		
		public StringExpressionStatement(String statement) {
			this.statement = statement;
		}
		
		public void setIgnoreThis() {
			this.ignoreThis = true;
		}
		
		public boolean ignoreThis() {
			return this.ignoreThis;
		}
		
		@Override
		public Kind getKind() {
			return null;
		}
		
		@Override
		public <R, D> R accept(TreeVisitor<R, D> arg0, D arg1) {
			return null;
		}
		
		@Override
		public ExpressionTree getExpression() {
			return null;
		}
		
		@Override
		public String toString() {
			return statement;
		}
		
	}
	
	private class ParamInfo {
		ActionMethodParam param;
		IJExpression assignment;
		JBlock runnableBlock;
		
		public ParamInfo(ActionMethodParam param, JBlock runnableBlock, IJExpression assignment) {
			super();
			this.param = param;
			this.runnableBlock = runnableBlock;
			this.assignment = assignment;
		}		
	}
	
	private class BlockDescription {
		JBlock block;
		String description;
		
		public BlockDescription(JBlock block, String description) {
			super();
			this.block = block;
			this.description = description;
		}
		
	}
	
	private static class ActionDetectedException extends RuntimeException {
		private static final long serialVersionUID = 1L;
	}
	
	private static class ActionProcessingException extends RuntimeException {

		private static final long serialVersionUID = 1L;

		public ActionProcessingException(String message) {
			super(message);
		}
		
	}
}

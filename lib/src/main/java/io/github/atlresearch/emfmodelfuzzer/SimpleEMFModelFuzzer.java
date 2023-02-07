package io.github.atlresearch.emfmodelfuzzer;

import java.lang.Class;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Random;

import org.eclipse.emf.common.util.EList;
import org.eclipse.emf.ecore.EAttribute;
import org.eclipse.emf.ecore.EClass;
import org.eclipse.emf.ecore.EClassifier;
import org.eclipse.emf.ecore.EEnum;
import org.eclipse.emf.ecore.EEnumLiteral;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.EReference;
import org.eclipse.emf.ecore.EStructuralFeature;
import org.eclipse.emf.ecore.resource.Resource;
//import org.eclipse.papyrus.aof.core.impl.operation.Inspect;
//import org.eclipse.uml2.uml.UMLPackage;

// TODO: enable CLEAR, ADD_MANY, and REMOVE_MANY
public class SimpleEMFModelFuzzer {

	private Resource modelToFuzz;
	private Random random;
	private boolean logToConsole;

	// set to true so that the next change is immediately undone
	// remark: undoing will set it back to false
	public boolean undo = false;

	public SimpleEMFModelFuzzer(Resource modelToFuzz, long seed, boolean logToConsole) {
		this.modelToFuzz = modelToFuzz;
		this.random = new Random(seed);
		this.logToConsole = logToConsole;
	}

	public static enum ManyChangeKind {
		ADD, REMOVE, MOVE,
		SET // REPLACE
		// 2 still unsupported:
		//	ADD_MANY
		//	REMOVE_MANY (including CLEAR)
		;
	}

	public static enum SingleChangeKind {
		SET, SET_NULL, UNSET;
	}

	// TODO: include elements that were discarded but can be readded later
	private Object getPossibleValue(EStructuralFeature feature, List<EObject> allContents) {
		Object ret = null;
		if(feature instanceof EAttribute) {
			EClassifier type = feature.getEType();
			Class<?> instanceClass = type.getInstanceClass();
			if(type instanceof EEnum) {
				List<EEnumLiteral> literals = ((EEnum)type).getELiterals();
				ret = literals.get(random.nextInt(literals.size())).getInstance();
			} else if(instanceClass.equals(Integer.class) || instanceClass.equals(Integer.TYPE)) {
				ret = random.nextInt();
			} else if(instanceClass.equals(String.class)) {
				ret = "" + random.nextInt();
			} else if(instanceClass.equals(Double.class) || instanceClass.equals(Double.TYPE)) {
				ret = random.nextDouble();
			} else if(instanceClass.equals(Boolean.class) || instanceClass.equals(Boolean.TYPE)) {
				ret = random.nextBoolean();
			} else {
				throw new UnsupportedOperationException("Cannot get a value of type " + type.getInstanceClass());
			}
		} else {
			if(random.nextBoolean()) {
				// add existing
				List<EObject> candidates = new ArrayList<EObject>();
				for(EObject o : allContents) {
					if(feature.getEType().isInstance(o)) {
						candidates.add(o);
					}
				}
				if(candidates.size() > 0) {
					ret = candidates.get(random.nextInt(candidates.size()));
				} else {
					ret = null;
				}
			} else {
				// add new
				EClass eClass = (EClass)feature.getEType();
				List<EClass> subtypes = getSubtypes(eClass);
				eClass = subtypes.get(random.nextInt(subtypes.size()));
				ret = eClass.getEPackage().getEFactoryInstance().create(eClass);
			}
		}
		return ret;
	}

	private Map<EClass, List<EClass>> subtypesByEClass = new HashMap<EClass, List<EClass>>();

	// & self
	private List<EClass> getSubtypes(EClass eClass) {
		List<EClass> ret = subtypesByEClass.get(eClass);
		if(ret == null) {
			ret = new ArrayList<EClass>();
			for(Iterator<EObject> i = eClass.eResource().getAllContents() ; i.hasNext() ; ) {
				EObject e = i.next();
				if(e instanceof EClass) {
					EClass ec = (EClass)e;
					if(ec.getEAllSuperTypes().contains(eClass) && !ec.isAbstract()) {
						ret.add(ec);
					}
				}
			}
			if(!eClass.isAbstract()) {
				ret.add(eClass);
			}
			subtypesByEClass.put(eClass, ret);
		}
		return ret;
	}

	private Map<EClass, List<EStructuralFeature>> featuresByEClass = new HashMap<EClass, List<EStructuralFeature>>();
	public Collection<String> excludedFeatures = Arrays.asList(
            // add feature name to ignore here


/*          // UML specific excluded features

			// problem with these ones: some additional constraints beyond actual feature type are implemented in UML plugin (probably based on OCL constraints in UML metamodel)
			// and we do not know these
			"templateParameter", "ownedTemplateSignature",	"owningTemplateParameter", // would require extra constraints + unused in case study

			"annotatedElement"	// behaves like containment|container, but is not one
/**/
	);

	private List<EStructuralFeature> getFeatures(EClass eClass) {
		List<EStructuralFeature> ret = featuresByEClass.get(eClass);
		if(ret == null) {
			ret = new ArrayList<EStructuralFeature>();
			for(EStructuralFeature feature : eClass.getEAllStructuralFeatures()) {
				if(feature.isChangeable() && !feature.isDerived() && !excludedFeatures.contains(feature.getName())) {
					ret.add(feature);
				}
			}
		}
		return ret;
	}

	// and self
	private List<EObject> allContainers(EObject eo) {
		List<EObject> ret = new ArrayList<EObject>();
		ret.add(eo);
		while(eo.eContainer() != null) {
			ret.add(eo.eContainer());
			eo = eo.eContainer();
		}
		return ret;
	}

	private boolean wouldAddContainmentCycle(EObject eo, EStructuralFeature feature, Object newValue_) {
		if(feature instanceof EReference) {
			EObject newValue = (EObject)newValue_;
			EReference ref = (EReference)feature;
			if(ref.isContainment() && allContainers(eo).contains(newValue)) {
				return true;
			}
			if(ref.isContainer() && newValue == eo) {
				return true;
			}
		}
		return false;
	}

	public void performOneChange() {
		List<EObject> allContents = new ArrayList<EObject>();
		for(Iterator<EObject> i = modelToFuzz.getAllContents() ; i.hasNext() ; ) {
			EObject eo = i.next();
			if(getFeatures(eo.eClass()).size() > 0) {
				allContents.add(eo);
			}
		}
		int index = random.nextInt(allContents.size());
		EObject eo = allContents.get(index);
		List<EStructuralFeature> features = getFeatures(eo.eClass());
		EStructuralFeature feature = features.get(random.nextInt(features.size()));
		try {
			performOneChange(allContents, eo, feature);
		} catch(IllegalArgumentException e) {
			// protects against typing constraints that are narrower than actual feature type
			String featureName = feature.getName();
			String expectedMessage = "^new" + featureName.substring(0, 1).toUpperCase() + featureName.substring(1) + " must be an instance of .*$";
			if(e.getMessage().matches(expectedMessage)) {
				if(logToConsole) {
					System.out.println("\t\tFailed because of hidden typing constraint");
				}
			} else {
				throw e;
			}
		}
	}

	
	private void performOneChange(List<EObject> allContents, EObject eo, EStructuralFeature feature) {
		if(feature.isMany()) {
			EList<Object> value = (EList<Object>)eo.eGet(feature);
			int n = value.size();
			ManyChangeKind kind = ManyChangeKind.values()[random.nextInt(ManyChangeKind.values().length)];
			trace(eo, feature, kind, value);
			switch(kind) {
			case ADD:
				Object newValue = getPossibleValue(feature, allContents);
				if(logToConsole) {
				     System.out.println("\t\tnewValue = " + toString(newValue));
				}
				if(newValue == null) {
					if(logToConsole) {
						System.out.println("\t\tINVALID (because no possible value):");
					}
				} else if(feature.isUnique() && value.contains(newValue)) {
					if(logToConsole) {
						System.out.println("\t\tINVALID (because already in set):");
					}
				} else if(wouldAddContainmentCycle(eo, feature, newValue)) {
					if(logToConsole) {
						System.out.println("\t\tINVALID (because would add containment cycle):");
					}
				} else {
					int index;
					value.add(index = random.nextInt(n + 1), newValue);
					if(undo) {
						value.remove(index);
						undo = false;
					}
				}
				break;
			case REMOVE:
				if(n > 0) {
					Object oldValue;
					int index;
					oldValue = value.remove(index = random.nextInt(n));
					if(undo) {
						value.add(index, oldValue);
						undo = false;
					}
				} else {
					value.clear();
					if(logToConsole) {
						System.out.println("\t\tCONVERTED to clear (because of empty collection):");
					}
					if(undo) {
						undo = false;
					}
				}
				break;
//			case CLEAR:
//				value.clear();
//				break;
			case MOVE:
				if(n > 0) {
					int newIndex, oldIndex;
					value.move(newIndex = random.nextInt(n), oldIndex = random.nextInt(n));
					if(undo) {
						value.move(oldIndex, newIndex);
						undo = false;
					}
				} else {
					if(logToConsole) {
						System.out.println("\t\tINVALID (because of empty collection):");
					}
				}
				break;
			case SET:
				newValue = getPossibleValue(feature, allContents);
				if(logToConsole) {
					System.out.println("\t\tnewValue = " + toString(newValue));
				}
				if(newValue == null) {
					if(logToConsole) {
						System.out.println("\t\tINVALID (because no possible value):");
					}
				} else if(feature.isUnique() && value.contains(newValue)) {
					if(logToConsole) {
						System.out.println("\t\tINVALID (because already in set):");	// TODO: unless we replace that one
					}
				} else if(wouldAddContainmentCycle(eo, feature, newValue)) {
					if(logToConsole) {
						System.out.println("\t\tINVALID (because would add containment cycle):");
					}
				} else if(value.size() == 0) {
					if(logToConsole) {
						System.out.println("\t\tINVALID (because empty collection => nothing to replace):");
					}
				} else {
					int index;
					Object oldValue = value.set(index = random.nextInt(n), newValue);
					if(undo) {
						value.set(index, oldValue);
						undo = false;
					}
				}
				break;
			default:
				throw new UnsupportedOperationException("TODO: add support for " + kind);
			}
			if(undo) {
				System.out.println("Undo many not supported yet");
				undo = false;
			}
		} else {
			SingleChangeKind kind = SingleChangeKind.values()[random.nextInt(SingleChangeKind.values().length)];
			trace(eo, feature, kind, eo.eGet(feature));
			Object oldValue = eo.eGet(feature);
			switch(kind) {
			case SET:
				Object newValue = getPossibleValue(feature, allContents);
				if(logToConsole) {
					System.out.println("\t\tnewValue = " + toString(newValue));
				}
				if(wouldAddContainmentCycle(eo, feature, newValue)) {
					if(logToConsole) {
						System.out.println("\t\tINVALID (because would add containment cycle):");
					}
/*
				} else if(feature.equals(UMLPackage.eINSTANCE.getProfileApplication_AppliedProfile())) {
					if(logToConsole) {
						System.out.println("\t\tINVALID (because UML does not support this):");
					}
*/
				} else {
					if(feature.getName().startsWith("base_")) {
						// TODO: handle this more generically
						// stereotypes must be unset then set because of the way PapyrusStereotypeListener works
						// (i.e., it does not "notify the old value")
						eo.eUnset(feature);
					}
					eo.eSet(feature, newValue);
				}
				break;
			case SET_NULL:
				Class<?> instanceClass = feature.getEType().getInstanceClass();
				if(instanceClass != null && instanceClass.isPrimitive()) {
					eo.eUnset(feature);
					if(logToConsole) {
						System.out.println("\t\tCONVERTED to UNSET (because of primitive type):");
					}
				} else {
					eo.eSet(feature, null);
				}
				break;
			case UNSET:
				eo.eUnset(feature);
				break;
			default:
				throw new UnsupportedOperationException("TODO: add support for " + kind);
			}
			if(undo) {
				eo.eSet(feature, oldValue);
				undo = false;
			}
		}
		if(undo) {
			throw new IllegalStateException("undo should have been performed and disabled");
		}
	}

	private void trace(EObject eo, EStructuralFeature feature, Enum kind, Object value) {
		if(logToConsole) {
			System.out.println("\t" + toString(eo) + "." + feature.getName() + ": " + kind + " (oldValue: " + toString(value) + ")");
		}
	}


    private static Object toString(Object o) {
		// Inspect may provide custom output, but it is now commented out to avoid this class depending on it.
        // return Inspect.toString(o, null);
        return o;
    }
}


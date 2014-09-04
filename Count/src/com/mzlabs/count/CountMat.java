package com.mzlabs.count;

import java.math.BigInteger;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

import com.winvector.linalg.DenseVec;
import com.winvector.linalg.LinalgFactory;
import com.winvector.linalg.Matrix;
import com.winvector.linalg.jblas.JBlasMatrix;
import com.winvector.lp.LPEQProb;
import com.winvector.lp.LPException;
import com.winvector.lp.LPSoln;
import com.winvector.lp.impl.RevisedSimplexSolver;

/**
 * Count number of non-negative integer solutions to a linear system of equalities using
 * the even/odd method from:
 * @article{CPC:54639,
 * author = {MOUNT,JOHN},
 * title = {Fast Unimodular Counting},
 * journal = {Combinatorics, Probability and Computing},
 * volume = {9},
 * issue = {03},
 * month = {5},
 * year = {2000},
 * issn = {1469--2163},
 * pages = {277--285},
 * numpages = {9},
 * doi = {null}
 * }
 * 
 * Linear system must be such that x=0 is unique non-negative solution to A x = 0.
 * 
 * @author johnmount
 *
 */
public final class CountMat {
	private final CountingProblem prob;
	private final int m;
	private final Map<IntVec,Map<IntVec,BigInteger>> zeroOneCounts;
	
	/**
	 * check that x = 0 is the unique non-negative solution to A x = 0
	 * @param A
	 * @throws LPException 
	 */
	private static <Z extends Matrix<Z>> String matrixFlaw(final LinalgFactory<Z> factory, final int[][] A) {
		final int m = A.length;
		final int n = A[0].length;
		// check for empty columns (LP does catch these, but easier to read message if we get them here)
		for(int j=0;j<n;++j) {
			boolean sawNZValue = false;
			for(int i=0;i<m;++i) {
				if(A[i][j]!=0) {
					sawNZValue = true;
					break;
				}
			}
			if(!sawNZValue) {
				return "matrix column " + j + " is all zero (unbounded or empty system)";
			}
		}
		try {
			final Z am = factory.newMatrix(m,n,false);
			for(int i=0;i<m;++i) {
				for(int j=0;j<n;++j) {
					if(A[i][j]!=0) {
						am.set(i, j, A[i][j]);
					}
				}
			}
			final double[] c = new double[n];
			Arrays.fill(c,-1.0);
			final LPEQProb prob = new LPEQProb(am.columnMatrix(),new double[m],new DenseVec(c));
			final RevisedSimplexSolver solver = new RevisedSimplexSolver();
			final LPSoln soln = solver.solve(prob, null, 0.0, 1000, factory);
			final double[] x = soln.primalSolution.toArray(n);
			boolean bad = false;
			for(final double xi: x) {
				if(Math.abs(xi)>1.0e-6) {
					bad = true;
					break;
				}
			}
			if(bad) {
				return "strictly positive solution to A x = 0: ";
			}
			return null; // no problem
		} catch (LPException ex) {
			return ex.toString();
		}
	}
	
	
	/**
	 * build all the zero/one lookup tables using a simple enumerate all zero one interiors (2^n complexity, not the n^m we want)
	 * @param A
	 * @return map from modul-2 class of rhs to rhs to count
	 */
	public static Map<IntVec,BigInteger> zeroOneSolutionCounts(final int[][] A) {
		final Map<IntVec,BigInteger> zeroOneCounts = new HashMap<IntVec,BigInteger>(10000);
		final int m = A.length;
		final int n = A[0].length;
		// build all possible zero/one sub-problems
		final IntLinOp Aop = new IntLinOp(A);
		final int[] z = new int[n];
		final int[] r = new int[m];
		do {
			Aop.mult(z,r);
			final IntVec rvec = new IntVec(r);
			BigInteger nzone = zeroOneCounts.get(rvec);
			if(null==nzone) {
				nzone = BigInteger.ONE;
			} else {
				nzone = nzone.add(BigInteger.ONE);
			}
			zeroOneCounts.put(rvec,nzone);
		} while(IntVec.advanceLT(2,z));
		return zeroOneCounts;
	}
	
	/**
	 * 
	 * @param counts Map b to number of solutions to A z = b for z zero/one (okay to omit unsolvable systems)
	 * @return Map from (b mod 2) to b to number of solutions to A z = b (all unsolvable combination omitted)
	 */
	private Map<IntVec,Map<IntVec,BigInteger>> organizeZeroOneStructures(final Map<IntVec,BigInteger> counts) {
		final Map<IntVec,Map<IntVec,BigInteger>> zeroOneCounts = new HashMap<IntVec,Map<IntVec,BigInteger>>(10000);
		for(final Map.Entry<IntVec,BigInteger> me: counts.entrySet()) {
			final IntVec b = me.getKey();
			final BigInteger c = me.getValue();
			if(c.compareTo(BigInteger.ZERO)>0) {
				final IntVec groupVec = modKVec(2,b);
				Map<IntVec,BigInteger> bgroup = zeroOneCounts.get(groupVec);
				if(null==bgroup) {
					bgroup = new HashMap<IntVec,BigInteger>();
					zeroOneCounts.put(groupVec,bgroup);
				}
				final BigInteger ov = bgroup.get(b);
				if(null==ov) {
					bgroup.put(b,c);
				} else {
					if(ov.compareTo(c)!=0) {
						throw new IllegalArgumentException("zero one data doesn't obey expected symmetries");
					}
				}
			}
		}
		return zeroOneCounts;
	}
	
	/**
	 * 
	 * @param A a matrix where x=0 is the unique non-negative solution to A x = 0
	 */
	public CountMat(final CountingProblem prob, boolean useDCZO) {
		this.prob = prob;
		m = prob.A.length;
		// check conditions
		final String problem = matrixFlaw(JBlasMatrix.factory,prob.A);
		if(null!=problem) {
			throw new IllegalArgumentException("unnacceptable matrix: " + problem);
		}
		// build all possible zero/one sub-problems
		final Map<IntVec,BigInteger> countsByB;
		if(useDCZO) {
			countsByB = DivideAndConquer.zeroOneSolutionCounts(prob.A);
		} else {
			countsByB = zeroOneSolutionCounts(prob.A);
		}
		zeroOneCounts = organizeZeroOneStructures(countsByB);
	}
	
	private static IntVec modKVec(final int k, final IntVec x) {
		final int n = x.dim();
		final int[] xm = new int[n];
		for(int i=0;i<n;++i) {
			xm[i] = x.get(i)%k;			
		}
		return new IntVec(xm);
	}
	

	
	/**
	 * assumes finite number of solutions (all variables involved) and A non-negative
	 * @param b non-negative vector
	 * @return number of non-negative integer solutions x to A x == b
	 */
	private BigInteger countNonNegativeSolutions(final int[] bIn, final Map<IntVec,BigInteger> nonnegCounts) {
		// check for base case
		boolean allZero = true;
		for(final int bi: bIn) {
			if(bi!=0) {
				allZero = false;
				break;
			}
		}
		if(allZero) {
			return BigInteger.ONE;
		}
		final IntVec b = new IntVec(bIn);
		final IntVec bNormal = prob.normalForm(b);
		BigInteger cached = nonnegCounts.get(bNormal);
		if(null==cached) {
			cached = BigInteger.ZERO;
			final Map<IntVec,BigInteger> group = zeroOneCounts.get(modKVec(2,b));
			if((null!=group)&&(!group.isEmpty())) {
				final int[] bprime = new int[m];
				for(final Map.Entry<IntVec,BigInteger> me: group.entrySet()) {
					final IntVec r = me.getKey();
					boolean goodR = true;
					for(int i=0;i<m;++i) {
						final int diff = b.get(i) - r.get(i);
						if((diff<0)||((diff&0x1)!=0)) {
							goodR = false;
							break;
						}
					}
					if(goodR) {
						final BigInteger nzone = me.getValue();
						for(int i=0;i<m;++i) {
							bprime[i] = (b.get(i) - r.get(i))/2;
						}
						final BigInteger subsoln = countNonNegativeSolutions(bprime,nonnegCounts);
						cached = cached.add(nzone.multiply(subsoln));
					}
				}
			}
			nonnegCounts.put(bNormal,cached);
			//System.out.println(b + " " + cached);
		}
		return cached;
	}
	
	public BigInteger countNonNegativeSolutions(final int[] b) {
		for(final int bi: b) {
			if(bi<0) {
				throw new IllegalArgumentException("negative b entry");
			}
		}
		final HashMap<IntVec, BigInteger> cache = new HashMap<IntVec,BigInteger>(10000);
		final BigInteger result = countNonNegativeSolutions(b,cache);
		//final Set<BigInteger> values = new HashSet<BigInteger>(cache.values());
		//System.out.println("cached " + cache.size() + " keys for " + values.size() + " values");
		return result;
	}
	




	/**
	 * assumes all variables involved and A non-negative and no empty columns
	 * @param A
	 * @param b
	 * @return number of non-negative integer solutions of A x = b
	 */
	public static BigInteger bruteForceSolnDebug(final int[][] A, final int[] b) {
		final int m = A.length;
		final int n = A[0].length;
		// inspect that A meets assumed conditions
		final boolean[] sawPos = new boolean[n];
		for(int i=0;i<m;++i) {
			for(int j=0;j<n;++j) {
				if(A[i][j]<0) {
					throw new IllegalArgumentException("negative matrix entry");
				}
				if(A[i][j]>0) {
					sawPos[j] = true;
				}
			}
		}
		for(final boolean pi: sawPos) {
			if(!pi) {
				throw new IllegalArgumentException("empty matrix column");
			}
		}
		final int[] bounds = new int[n];
		Arrays.fill(bounds,Integer.MAX_VALUE);
		for(int i=0;i<m;++i) {
			for(int j=0;j<n;++j) {
				if(A[i][j]>0) {
					bounds[j] = Math.min(bounds[j],b[i]/A[i][j]);
				}
			}
		}
		final IntVec boundsV = new IntVec(bounds);
		BigInteger count = BigInteger.ZERO;
		final int[] x = new int[n];
		final int[] r = new int[m];
		do {
			IntLinOp.mult(A,x,r);
			boolean goodR = true;
			for(int i=0;i<m;++i) {
				if(b[i]!=r[i]) {
					goodR = false;
					break;
				}
			}
			if(goodR) {
				count = count.add(BigInteger.ONE);
			}
		} while(boundsV.advanceLE(x));
		return count;
	}
	
	

}
package jsat.datatransform.kernel;

import java.util.*;
import jsat.DataSet;
import jsat.classifiers.DataPoint;
import jsat.clustering.HamerlyKMeans;
import jsat.clustering.SeedSelectionMethods;
import jsat.datatransform.*;
import jsat.distributions.kernels.CacheAcceleratedKernel;
import jsat.distributions.kernels.KernelTrick;
import jsat.linear.*;
import jsat.linear.distancemetrics.EuclideanDistance;
import jsat.utils.DoubleList;
import jsat.utils.random.XOR96;

/**
 * An implementation of the Nystrom approximation for any Kernel Trick. The full
 * rank kernel is approximated by a basis set of a subset of the data points 
 * that make up the original data set. Instead of explicitly forming the 
 * approximately decomposed matrix, this transform projects the original numeric
 * features of a data set into a new feature space where the dot product in the 
 * linear space approximates the dot product in the given kernel space. 
 * <br><br>
 * See: <br>
 * <ul>
 * <li>Williams, C., & Seeger, M. (2001). <i>Using the Nyström Method to Speed 
 * Up Kernel Machines</i>. Advances in Neural Information Processing Systems 13 
 * (pp. 682–688). MIT Press. Retrieved from 
 * <a href="http://citeseerx.ist.psu.edu/viewdoc/summary?doi=10.1.1.18.7519">
 * here</a></li>
 * <li>Yang, T., Li, Y.-F., Mahdavi, M., Jin, R., & Zhou, Z.-H. (2012). <i>
 * Nystrom Method vs Random Fourier Features A Theoretical and Empirical 
 * Comparison</i>. Advances in Neural Information Processing Systems 
 * (pp. 485–493). Retrieved from 
 * <a href="http://books.nips.cc/papers/files/nips25/NIPS2012_0248.txt">here</a>
 * </li>
 * <li>Kumar, S., Mohri, M., & Talwalkar, A. (2012). <i>Sampling methods for the
 * Nyström method</i>. The Journal of Machine Learning Research, 5, 981–1006. 
 * Retrieved from <a href="http://dl.acm.org/citation.cfm?id=2343678">here</a>
 * </li>
 * </ul>
 * @author Edward Raff
 */
public class Nystrom implements DataTransform
{
    private KernelTrick k;
    private List<Vec> basisVecs;
    private List<Double> accelCache;
    private Matrix transform;
    private boolean sampleWithReplacment = false;

    /**
     * Different sample methods may be used to select a better and more 
     * representative set of vectors to form the basis vectors at increased 
     * cost, where {@code n} is the number of data points in the full data set 
     * and {@code b} is the number of basis vectors to obtain. 
     */
    public enum SamplingMethod
    {
        /**
         * Selects the basis vectors by uniform sampling, takes O(b) time
         */
        UNIFORM, 
        /**
         * Selects the basis vectors by a weighted probability of the kernel 
         * value of k(x<sub>i</sub>, x<sub>i</sub>) for each <i>i</i>. If a 
         * kernel returns 1 for all k(x<sub>i</sub>, x<sub>i</sub>), then this 
         * reduces the uniform sampling. Takes O(n) time
         */
        DIAGONAL,
        /**
         * Selects the basis vectors by the weighted probability of the column 
         * norms of the gram matrix for each vector. Takes O(n<sup>2</sup>) time
         */
        NORM,
        /**
         * Selects the basis vectors as the means of a k-means clustering. Takes
         * the time needed to perform k-means
         */
        KMEANS,
    }
    
    /**
     * Creates a new Nystrom approximation object
     * @param k the kernel trick to form an approximation of
     * @param dataset the data set to form the approximate feature space from
     * @param basisSize the number of basis vectors to use, this is the output 
     * dimension size.
     * @param method what sampling method should be used to select the basis 
     * vectors from the full data set. 
     */
    public Nystrom(KernelTrick k, DataSet dataset, int basisSize, SamplingMethod method)
    {
        this(k, dataset, basisSize, method, false);
    }

    /**
     * Creates a new Nystrom approximation object
     * @param k the kernel trick to form an approximation of
     * @param dataset the data set to form the approximate feature space from
     * @param basisSize the number of basis vectors to use, this is the output 
     * dimension size.
     * @param method what sampling method should be used to select the basis 
     * vectors from the full data set. 
     * @param sampleWithReplacment {@code true} if the basis vectors should be 
     * sampled with replacement, {@code false} if they should not. 
     */
    @SuppressWarnings("fallthrough")
    public Nystrom(KernelTrick k, DataSet dataset, int basisSize, SamplingMethod method, boolean sampleWithReplacment )
    {
        Random rand = new XOR96();

        final int N = dataset.getSampleSize();
        final int D = dataset.getNumNumericalVars();
        this.sampleWithReplacment = sampleWithReplacment;
        final List<Vec> X = dataset.getDataVectors();

        //Create smaller gram matrix K and decompose is
        basisVecs = new ArrayList<Vec>(basisSize);
        switch (method)
        {
            case DIAGONAL:
                double[] diags = new double[N];
                diags[0] = kEval(0, 0);
                for (int i = 1; i < N; i++)
                    diags[i] = diags[0] + kEval(i, i);
                sample(basisSize, rand, diags, X);
                break;
            case NORM:
                double[] norms = new double[N];
                List<Vec> gramVecs = new ArrayList<Vec>();
                for (int i = 0; i < N; i++)
                    gramVecs.add(new DenseVector(N));

                if (k instanceof CacheAcceleratedKernel)
                {
                    CacheAcceleratedKernel kc = (CacheAcceleratedKernel) k;
                    List<Double> tmpCache = kc.getCache(X);
                    for (int i = 0; i < N; i++)
                    {
                        gramVecs.get(i).set(i, kc.eval(i, i, X, tmpCache));
                        for (int j = i + 1; j < N; j++)
                        {
                            double val = kc.eval(i, j, X, tmpCache);
                            gramVecs.get(i).set(j, val);
                            gramVecs.get(j).set(i, val);
                        }
                    }
                }
                else
                {
                    for (int i = 0; i < N; i++)
                    {
                        gramVecs.get(i).set(i, k.eval(X.get(i), X.get(i)));
                        for (int j = i + 1; j < N; j++)
                        {
                            double val = k.eval(X.get(i), X.get(j));
                            gramVecs.get(i).set(j, val);
                            gramVecs.get(j).set(i, val);
                        }
                    }
                }

                norms[0] = gramVecs.get(0).pNorm(2);
                for (int i = 1; i < gramVecs.size(); i++)
                    norms[i] = norms[i - 1] + gramVecs.get(i).pNorm(2);
                sample(basisSize, rand, norms, X);
                break;
            case KMEANS:
                HamerlyKMeans kMeans = new HamerlyKMeans(new EuclideanDistance(), SeedSelectionMethods.SeedSelection.KPP);
                kMeans.setStoreMeans(true);
                kMeans.cluster(dataset, basisSize);
                basisVecs.addAll(kMeans.getMeans());
            case UNIFORM:
            default:
                if (sampleWithReplacment)
                {
                    Set<Integer> sampled = new HashSet<Integer>(basisSize);
                    while (sampled.size() < basisSize)
                        sampled.add(rand.nextInt(N));
                    for (int indx : sampled)
                        basisVecs.add(X.get(indx));
                }
                else
                    for (int i = 0; i < basisSize; i++)
                        basisVecs.add(X.get(rand.nextInt(N)));

        }

        setKernel(k);

        Matrix K = new DenseMatrix(basisSize, basisSize);
        for (int i = 0; i < basisSize; i++)
        {
            K.set(i, i, kEval(i, i));
            for (int j = i + 1; j < basisSize; j++)
            {
                double val = kEval(i, j);
                K.set(i, j, val);
                K.set(j, i, val);
            }
        }

        //Decompose it
        EigenValueDecomposition eig = new EigenValueDecomposition(K);

        double[] eigenVals = eig.getRealEigenvalues();
        DenseVector eigNorm = new DenseVector(eigenVals.length);
        for (int i = 0; i < eigenVals.length; i++)
            eigNorm.set(i, 1.0 / Math.sqrt(eigenVals[i]));

        //U * 1/sqrt(S)
        Matrix U = eig.getV();
        Matrix.diagMult(U, eigNorm);
        transform = U.multiply(eig.getVRaw());
        transform.mutableTranspose();
    }

    /**
     * Copy constructor
     * @param toCopy the object to copy
     */
    protected Nystrom(Nystrom toCopy)
    {
        this.k = toCopy.k.clone();
        this.basisVecs = new ArrayList<Vec>(toCopy.basisVecs);
        if(toCopy.accelCache != null)
            this.accelCache = new DoubleList(toCopy.accelCache);
        this.transform = toCopy.transform.clone();
    }
    
    /**
     * Performs waited sampling on the cumulative sum of all values mapped to 
     * each vector. The sampled vectors will be placed directly into {@link #basisVecs}
     * @param basisSize the number of basis vectors to sample for
     * @param rand the source of randomness
     * @param weightSume the cumulative weight sum
     * @param X the list of vectors
     */
    private void sample(int basisSize, Random rand, double[] weightSume, List<Vec> X)
    {
        Set<Integer> sampled = new HashSet<Integer>(basisSize);
        
        double max = weightSume[weightSume.length-1];
        for(int i = 0; i < basisSize; i++)
        {
            double rndVal = rand.nextDouble()*max;
            int indx = Arrays.binarySearch(weightSume, rndVal);
            if(indx < 0)
                indx = (-(indx) - 1);
            if(sampleWithReplacment)//no need to do anything
                basisVecs.add(X.get(indx));
            else
            {
                int size = sampled.size();
                sampled.add(indx);
                if(sampled.size() == size)
                    i--;//do it again
                else
                    basisVecs.add(X.get(indx));
            }
        }
    }
    
    private double kEval(int i, int j)
    {
        if(k instanceof CacheAcceleratedKernel)
            return ((CacheAcceleratedKernel)k).eval(i, j, basisVecs, accelCache);
        else
            return k.eval(basisVecs.get(i), basisVecs.get(j));
    }

    @Override
    public DataPoint transform(DataPoint dp)
    {
        Vec x = dp.getNumericalValues();
        Vec kVec = new DenseVector(basisVecs.size());
        for(int i = 0; i < basisVecs.size(); i++)
            kVec.set(i, k.eval(basisVecs.get(i), x));
        return new DataPoint(kVec.multiply(transform), dp.getCategoricalValues(), dp.getCategoricalData(), dp.getWeight());
    }

    @Override
    public Nystrom clone()
    {
        return new Nystrom(this);
    }

    private void setKernel(KernelTrick k)
    {
        this.k = k;
        if(k instanceof CacheAcceleratedKernel)
            accelCache = ((CacheAcceleratedKernel)k).getCache(basisVecs);
    }
    
    static public class NystromTransformFactory implements DataTransformFactory
    {
        private KernelTrick k;
        private int dimension;
        private SamplingMethod method;
        private boolean sampleWithReplacment;
             
        /**
         * Creates a new Nystrom object
         * @param k the kernel trick to form an approximation of
         * @param dimension the number of basis vectors to use, this is the 
         * output dimension size.
         * @param method what sampling method should be used to select the basis 
         * vectors from the full data set. 
         * @param sampleWithReplacment {@code true} if the basis vectors should 
         * be sampled with replacement, {@code false} if they should not. 
         */
        public NystromTransformFactory(KernelTrick k, int dimension, SamplingMethod method, boolean sampleWithReplacment)
        {
            this.k = k;
            this.dimension = dimension;
            this.method = method;
            this.sampleWithReplacment = sampleWithReplacment;
        }

        @Override
        public DataTransform getTransform(DataSet dataset)
        {
            return new Nystrom(k, dataset, dimension, method, sampleWithReplacment);
        }
        
    }
}
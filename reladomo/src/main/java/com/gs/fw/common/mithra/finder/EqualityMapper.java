/*
 Copyright 2016 Goldman Sachs.
 Licensed under the Apache License, Version 2.0 (the "License");
 you may not use this file except in compliance with the License.
 You may obtain a copy of the License at

   http://www.apache.org/licenses/LICENSE-2.0

 Unless required by applicable law or agreed to in writing,
 software distributed under the License is distributed on an
 "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 KIND, either express or implied.  See the License for the
 specific language governing permissions and limitations
 under the License.
 */

package com.gs.fw.common.mithra.finder;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.gs.collections.impl.set.mutable.UnifiedSet;
import com.gs.fw.common.mithra.MithraObjectPortal;
import com.gs.fw.common.mithra.attribute.AsOfAttribute;
import com.gs.fw.common.mithra.attribute.Attribute;
import com.gs.fw.common.mithra.attribute.MappedAttribute;
import com.gs.fw.common.mithra.cache.Cache;
import com.gs.fw.common.mithra.cache.ConcurrentFullUniqueIndex;
import com.gs.fw.common.mithra.cache.IndexReference;
import com.gs.fw.common.mithra.extractor.Extractor;
import com.gs.fw.common.mithra.finder.asofop.AsOfEqOperation;
import com.gs.fw.common.mithra.notification.MithraDatabaseIdentifierExtractor;
import com.gs.fw.common.mithra.util.InternalList;
import com.gs.fw.common.mithra.util.ListFactory;
import com.gs.fw.common.mithra.util.MithraFastList;
import com.gs.reladomo.metadata.PrivateReladomoClassMetaData;


public class EqualityMapper extends AbstractMapper implements Cloneable
{
    private Attribute left;
    private Attribute right;
    private boolean isAutoGenerated;
    private boolean isLeftMapped;
    private boolean isRightMapped;

    private transient IndexReference leftIndexRef;

    public EqualityMapper(Attribute left, Attribute right)
    {
        init(left, right);
        //todo: fix the mapped case for reverse mappers. simply swapping left and right is not correct. See TestFilterEquality.testDeepFilterEq
        this.setReverseMapper(new EqualityMapper(right, left, this));
    }

    private void init(Attribute left, Attribute right)
    {
        this.left = left;
        this.right = right;
        this.isLeftMapped = (left instanceof MappedAttribute);
        this.isRightMapped = (right instanceof MappedAttribute);
    }

    public EqualityMapper(Attribute left, Attribute right, boolean anonymous)
    {
        this(left, right);
        this.setAnonymous(anonymous);
    }

    public EqualityMapper(Attribute left, Attribute right, EqualityMapper reverseMapper)
    {
        init(left, right);
        this.setReverseMapper(reverseMapper);
    }

    public boolean hasMappedAttributes()
    {
        return isLeftMapped || isRightMapped;
    }

    public MithraObjectPortal getResultPortal()
    {
        //this doesn't work for mapped attributes!
        if (left instanceof MappedAttribute)
        {
            return ((MappedAttribute) left).getMapper().getResultPortal();
        }
        return this.left.getOwnerPortal();
    }

    public MithraObjectPortal getFromPortal()
    {
        return this.right.getOwnerPortal();
    }

    public boolean isReversible()
    {
        return true;
    }

    public boolean addsToWhereClause()
    {
        return !(left.isSourceAttribute() || right.isSourceAttribute() || left.isAsOfAttribute() || right.isAsOfAttribute());
    }

    public void registerOperation(MithraDatabaseIdentifierExtractor extractor, boolean registerEquality)
    {
        boolean needToGenerate = !extractor.isMappedAlready(this);
        registerOperationForLeft(extractor, registerEquality, needToGenerate);
        if (!this.isRightMapped) extractor.pushMapper(this);
        registerOperationForRight(extractor, registerEquality, needToGenerate);
    }

    protected void registerOperationForRight(MithraDatabaseIdentifierExtractor extractor, boolean registerEquality, boolean needToGenerate)
    {
        if (needToGenerate)
        {
            if (registerEquality && !(this.right instanceof MappedAttribute)) extractor.registerRelatedAttributeEqualityFromMapper(this.right);
            if (left.isSourceAttribute() || right.isSourceAttribute())
            {
                extractor.setEqualitySourceOperation();
            }
            else
            {
                registerOperation(right, extractor, registerEquality, false);
            }
        }
    }

    protected void registerOperationForLeft(MithraDatabaseIdentifierExtractor extractor, boolean registerEquality, boolean needToGenerate)
    {
        if (needToGenerate && !left.isSourceAttribute())
        {
            registerOperation(left, extractor, registerEquality, true);
        }
    }

    public void generateSql(SqlQuery query)
    {
        boolean needToGenerate = !(query.isMappedAlready(this));
        String fullyQualifiedLeftColumnName = generateLeftHandSql(query, needToGenerate);
        generateRightHandSql(query, true, needToGenerate, fullyQualifiedLeftColumnName);
        if (needToGenerate)
        {
            query.addAsOfAttributeSql();
        }
    }

    protected void generateRightHandSql(SqlQuery query, boolean checkAlreadyMapped, boolean needToGenerate, String fullyQualifiedLeftColumnName)
    {
        if (checkAlreadyMapped && !this.isRightMapped) query.pushMapper(this);
        boolean insertedAnd = false;
        if (this.isLeftMapped)
        {
            insertedAnd = query.beginAnd();
        }
        if (needToGenerate && !(left.isSourceAttribute() || right.isSourceAttribute()))
        {
            if (this.isRightMapped)
            {
                MappedAttribute mappedAttribute = (MappedAttribute) this.right;
                Mapper mapper = mappedAttribute.getMapper();
                mapper.generateSql(query);
                query.generateJoinSql(fullyQualifiedLeftColumnName, mappedAttribute.getWrappedAttribute().getFullyQualifiedLeftHandExpression(query), "=");
            }
            else
            {
                query.generateJoinSql(fullyQualifiedLeftColumnName, right.getFullyQualifiedLeftHandExpression(query), "=");
            }
        }
        query.endAnd(insertedAnd);
    }

    protected String generateLeftHandSql(SqlQuery query, boolean needToGenerate)
    {
        String fullyQualifiedLeftColumnName = null;
        if (needToGenerate && !left.isSourceAttribute())
        {
            if (this.isLeftMapped)
            {
                fullyQualifiedLeftColumnName = generateMapperSql(left, query);
            }
            else
            {
                fullyQualifiedLeftColumnName = left.getFullyQualifiedLeftHandExpression(query);
            }
        }
        return fullyQualifiedLeftColumnName;
    }


    private void registerOperation(Attribute attribute, MithraDatabaseIdentifierExtractor extractor, boolean registerEquality, boolean pop)
    {
        if (attribute instanceof MappedAttribute)
        {
            MappedAttribute mappedAttribute = (MappedAttribute) attribute;
            Mapper mapper = mappedAttribute.getMapper();
            mapper.registerOperation(extractor, registerEquality);
            if (registerEquality) extractor.registerRelatedAttributeEqualityFromMapper(attribute);
            //todo: this seems to depend on left join or not???
//            extractor.setMustJoin(extractor.getCurrentMapperList());
            if (pop) mapper.popMappers(extractor);
        }
    }

    private String generateMapperSql(Attribute attribute, SqlQuery query)
    {
        if (attribute instanceof MappedAttribute)
        {
            MappedAttribute mappedAttribute = (MappedAttribute) attribute;
            Mapper mapper = mappedAttribute.getMapper();
            mapper.generateSql(query);
            String fullyQualifiedLeft = mappedAttribute.getWrappedAttribute().getFullyQualifiedLeftHandExpression(query);
            mapper.popMappers(query);
            return fullyQualifiedLeft;
        }
        return null;
    }

    public int getClauseCount(SqlQuery query)
    {
        return 1;
    }

    public void addDepenedentPortalsToSet(Set set)
    {
        left.zAddDependentPortalsToSet(set);
        right.zAddDependentPortalsToSet(set);
    }

    public void addDepenedentAttributesToSet(Set set)
    {
        left.zAddDepenedentAttributesToSet(set);
        right.zAddDepenedentAttributesToSet(set);
    }

    public Attribute getDeepestEqualAttribute(Attribute attribute)
    {
        if (attribute.equals(this.left)) return this.right;
        return null;
    }

    public Mapper and(Mapper other)
    {
        return other.andWithEqualityMapper(this);
    }

    public Mapper andWithMultiEqualityMapper(MultiEqualityMapper other)
    {
        return other.andWithEqualityMapper(this);
    }

    public Mapper andWithEqualityMapper(EqualityMapper other)
    {
        return new MultiEqualityMapper(this, other);
    }

    protected MappedOperation combineByType(MappedOperation mappedOperation, MappedOperation otherMappedOperation)
    {
        if (this.hasMappedAttributes()) return null;
        return otherMappedOperation.getMapper().combineWithEqualityMapper(otherMappedOperation, mappedOperation);
    }

    public MappedOperation combineWithEqualityMapper(MappedOperation mappedOperation, MappedOperation otherMappedOperation)
    {
        if (this.hasMappedAttributes()) return null;
        if (otherMappedOperation.isSameMapFromTo(mappedOperation))
        {
            MultiEqualityMapper newMapper = new MultiEqualityMapper((EqualityMapper)otherMappedOperation.getMapper(),
                    (EqualityMapper)mappedOperation.getMapper());
            return new MappedOperation(newMapper,
                    otherMappedOperation.getUnderlyingOperation().and(mappedOperation.getUnderlyingOperation()));
        }
        return null;
    }

    public MappedOperation combineWithMultiEqualityMapper(MappedOperation mappedOperation, MappedOperation otherMappedOperation)
    {
        if (this.hasMappedAttributes()) return null;
        if (otherMappedOperation.isSameMapFromTo(mappedOperation))
        {
            Mapper newMapper = new MultiEqualityMapper((MultiEqualityMapper) otherMappedOperation.getMapper(),
                    (EqualityMapper)mappedOperation.getMapper());
            return new MappedOperation(newMapper,
                    otherMappedOperation.getUnderlyingOperation().and(mappedOperation.getUnderlyingOperation()));
        }
        return null;
    }

    public MappedOperation equalitySubstituteWithAtomic(MappedOperation mappedOperation, AtomicOperation op)
    {
        if (op.getAttribute().equals(this.getLeft())  && !mappedOperation.underlyingOperationDependsOnAttribute(this.getRight()))
        {
            Operation replacedOp = op.susbtituteOtherAttribute(this.getRight());
            if (replacedOp != null)
            {
                return new MappedOperation(this,
                        mappedOperation.getUnderlyingOperation().and(replacedOp));
            }
        }
        return null;
    }

    public MappedOperation equalitySubstituteWithMultiEquality(MappedOperation mappedOperation, MultiEqualityOperation op)
    {
        if (!mappedOperation.underlyingOperationDependsOnAttribute(this.getRight()))
        {
            Operation susbstitutedEquality = op.getSusbstitutedEquality(this.getLeft(), this.getRight());
            if (susbstitutedEquality != null)
            {
                return new MappedOperation(this,
                        mappedOperation.getUnderlyingOperation().and(susbstitutedEquality));
            }
        }
        return null;
    }

    protected Cache getCache()
    {
        return left.getOwnerPortal().getCache();
    }

    private IndexReference getLeftIndexRef()
    {
        Cache cache = this.getCache();
        if (this.leftIndexRef == null || !this.leftIndexRef.isForCache(cache))
        {
            leftIndexRef = cache.getIndexRef(left);
        }
        return leftIndexRef;
    }

    protected boolean isLeftIndexed()
    {
        return this.getLeftIndexRef().isValid();
    }

    public boolean mapUsesUniqueIndex()
    {
        return this.isLeftIndexed() && this.getCache().isUnique(this.getLeftIndexRef().indexReference);
    }

    public boolean mapUsesImmutableUniqueIndex()
    {
        return this.isLeftIndexed() && this.getCache().isUniqueAndImmutable(this.getLeftIndexRef().indexReference);
    }

    public boolean mapUsesNonUniqueIndex()
    {
        int indexRef = this.getLeftIndexRef().indexReference;
        return indexRef > 0 && indexRef != IndexReference.AS_OF_PROXY_INDEX_ID && !this.getCache().isUnique(this.getLeftIndexRef().indexReference);
    }

    public Attribute getLeft()
    {
        return left;
    }

    public Attribute getRight()
    {
        return right;
    }

    public int hashCode()
    {
        return left.hashCode() ^ right.hashCode();
    }

    public boolean equals(Object obj)
    {
        if (this == obj) return true;
        if (obj instanceof EqualityMapper)
        {
            EqualityMapper other = (EqualityMapper) obj;
            return other.left.equals(this.left) && other.right.equals(this.right) && this.isAutoGenerated == other.isAutoGenerated;
        }
        return false;
    }

    public void setAutoGenerated(boolean autoGenerated)
    {
        isAutoGenerated = autoGenerated;
    }

    public Mapper getReverseMapper()
    {
        return reverseMapper;
    }

    protected void setReverseMapper(Mapper reverseMapper)
    {
        this.reverseMapper = reverseMapper;
    }

    public void registerAsOfAttributesAndOperations(AsOfEqualityChecker checker)
    {
        registerLeftAsOfAttributesAndOperations(checker);
        if (!this.isRightMapped)
        {
            checker.pushMapper(this);
        }
        registerRightAsOfAttributesAndOperations(checker);
    }

    protected void registerRightAsOfAttributesAndOperations(AsOfEqualityChecker checker)
    {
        if (!isRightMapped)
        {
            checker.registerAsOfAttributes(right.getAsOfAttributes());
        }
        else
        {
            this.registerAsOfAttributesForMappedAttribute(right, checker, false);
        }
    }

    protected void registerLeftAsOfAttributesAndOperations(AsOfEqualityChecker checker)
    {
        if (!isLeftMapped)
        {
            checker.registerAsOfAttributes(left.getAsOfAttributes());
        }
        else
        {
            this.registerAsOfAttributesForMappedAttribute(left, checker, true);
        }
    }

    protected void registerAsOfAttributesForMappedAttribute(Attribute attribute, AsOfEqualityChecker checker, boolean pop)
    {
        if (attribute instanceof MappedAttribute)
        {
            MappedAttribute mappedAttribute = (MappedAttribute) attribute;
            mappedAttribute.getMapper().registerAsOfAttributesAndOperations(checker);
            checker.registerAsOfAttributes(attribute.getAsOfAttributes());
            if (pop) mappedAttribute.getMapper().popMappers(checker);
        }
    }

    public void pushMappers(MapperStack mapperStack)
    {
        if (!isRightMapped)
        {
            mapperStack.pushMapper(this);
        }
        else
        {
            MappedAttribute mappedAttribute = (MappedAttribute) right;
            mappedAttribute.getMapper().pushMappers(mapperStack);
        }
    }

    public void popMappers(MapperStack mapperStack)
    {
        if (!isRightMapped)
        {
            mapperStack.popMapper();
        }
        else
        {
            MappedAttribute mappedAttribute = (MappedAttribute) right;
            mappedAttribute.getMapper().popMappers(mapperStack);
        }
    }

    public List map(List joinedList)
    {
        Attribute right = this.right;
        List firstList = null;
        if (right instanceof MappedAttribute)
        {
            MappedAttribute mappedAttribute = (MappedAttribute) right;
            firstList = mappedAttribute.getMapper().map(joinedList);
            right = mappedAttribute.getWrappedAttribute();
        }
        List secondList = this.basicMap(right, joinedList);
        if (firstList != null && secondList != null)
        {
            secondList = MappedOperation.intersectLists(firstList, secondList);
        }
        return secondList;
    }

    public double estimateMappingFactor()
    {
        IndexReference indexRef = this.getLeftIndexRef();
        if (indexRef.isValid() && this.getCache().isInitialized(indexRef.indexReference))
        {
            return this.getCache().getAverageReturnSize(indexRef.indexReference, 1);
        }
        else
        {
            double leftSize = this.left.getOwnerPortal().getCache().estimateQuerySize();
            double rightSize = this.right.getOwnerPortal().getCache().estimateQuerySize();
            if (rightSize == 0) return 0;
            return leftSize / rightSize;
        }
    }

    @Override
    public int estimateMaxReturnSize(int multiplier)
    {
        IndexReference indexRef = this.getLeftIndexRef();
        if (indexRef.isValid() && this.getCache().isInitialized(indexRef.indexReference))
        {
            return this.getCache().getMaxReturnSize(indexRef.indexReference, multiplier);
        }
        else
        {
            double leftSize = this.left.getOwnerPortal().getCache().estimateQuerySize();
            double rightSize = this.right.getOwnerPortal().getCache().estimateQuerySize();
            if (rightSize == 0) return 0;
            return (int) Math.min(leftSize  * multiplier/ rightSize, this.getCache().estimateQuerySize());
        }
    }

    public void registerEqualitiesAndAtomicOperations(TransitivePropagator transitivePropagator)
    {
        transitivePropagator.pushMapper(this);
        transitivePropagator.setEquality(this.left, this.right);
    }

    public boolean hasTriangleJoins()
    {
        if (isLeftMapped && isRightMapped) return true;
        if (this.isLeftMapped && !isSingleLevelJoin((MappedAttribute) this.left))
        {
            return true;
        }
        if (this.isRightMapped && !isSingleLevelJoin((MappedAttribute) this.right))
        {
            return true;
        }
        return false;
    }

    private boolean isSingleLevelJoin(MappedAttribute mappedAttribute)
    {
        Mapper mapper = mappedAttribute.getMapper();
        return mapper.isSingleLevelJoin() && this.left.getOwnerPortal().equals(mapper.getResultPortal())
                && mappedAttribute.getWrappedAttribute().getOwnerPortal().equals(mapper.getFromPortal());
    }

    public boolean isRightHandPartialCacheResolvable()
    {
        return true;
    }

    public ConcurrentFullUniqueIndex mapMinusOneLevel(List joinedList)
    {
        return ConcurrentFullUniqueIndex.parallelConstructIndexWithoutNulls(joinedList, new Extractor[] { this.right });
    }

    public List mapOne(Object joined, Operation extraLeftOperation)
    {
        Attribute right = this.right;
        List firstList = null;
        if (right instanceof MappedAttribute)
        {
            MappedAttribute mappedAttribute = (MappedAttribute) right;
            firstList = mappedAttribute.getMapper().mapOne(joined, extraLeftOperation);
            right = mappedAttribute.getWrappedAttribute();
        }
        List secondList = this.basicMapOne(right, joined, extraLeftOperation);
        if (firstList != null && secondList != null)
        {
            secondList = MappedOperation.intersectLists(firstList, secondList);
        }
        return secondList;
    }

    public Operation getSimplifiedJoinOp(List parentList, int maxInClause, DeepFetchNode node, boolean useTuple)
    {
        Operation op = right.zInWithMax(maxInClause, parentList, left);
        if (op.zIsNone()) op = null;
        return op;
    }

    protected List basicMapOne(Attribute right, Object joined, Operation extraLeftOperation)
    {
        Operation operation = this.getLeft().in(ListFactory.create(joined), right);
        if (extraLeftOperation != null) operation = operation.and(extraLeftOperation);
        return operation.getResultObjectPortal().zFindInMemoryWithoutAnalysis(operation, true);
    }

    public List mapReturnNullIfIncompleteIndexHit(List joinedList)
    {
        Attribute right = this.right;
        List firstList = null;
        if (right instanceof MappedAttribute)
        {
            MappedAttribute mappedAttribute = (MappedAttribute) right;
            firstList = mappedAttribute.getMapper().map(joinedList);
            right = mappedAttribute.getWrappedAttribute();
        }
        List secondList = this.basicMapReturnNullIfIncompleteIndexHit(right, joinedList);
        if (firstList != null && secondList != null)
        {
            secondList = MappedOperation.intersectLists(firstList, secondList);
        }
        return secondList;
    }

    public List mapReturnNullIfIncompleteIndexHit(List joinedList, Operation extraOperationOnResult)
    {
        Attribute right = this.right;
        List firstList = null;
        if (right instanceof MappedAttribute)
        {
            MappedAttribute mappedAttribute = (MappedAttribute) right;
            firstList = mappedAttribute.getMapper().map(joinedList);
            right = mappedAttribute.getWrappedAttribute();
        }
        List secondList = this.basicMapReturnNullIfIncompleteIndexHit(right, joinedList, extraOperationOnResult);
        if (firstList != null && secondList != null)
        {
            secondList = MappedOperation.intersectLists(firstList, secondList);
        }
        return secondList;
    }

    protected List basicMap(Attribute right, List joinedList)
    {
        Operation operation = this.getLeft().in(joinedList, right);
        return operation.getResultObjectPortal().zFindInMemoryWithoutAnalysis(operation, true);
    }

    protected List basicMapReturnNullIfIncompleteIndexHit(Attribute right, List joinedList)
    {
        Operation operation = this.getLeft().in(joinedList, right);
        return operation.getResultObjectPortal().zFindInMemoryWithoutAnalysis(operation, true);
    }

    protected List basicMapReturnNullIfIncompleteIndexHit(Attribute right, List joinedList, Operation extraOperationOnResult)
    {
        Operation operation = this.getLeft().in(joinedList, right).and(extraOperationOnResult);
        return operation.getResultObjectPortal().zFindInMemoryWithoutAnalysis(operation, true);
    }

    public List map(List joinedList, Operation extraOperationOnResult)
    {
        return mapReturnNullIfIncompleteIndexHit(joinedList, extraOperationOnResult);
    }

    public List<Mapper> getUnChainedMappers()
    {
        return ListFactory.<Mapper>create(this);
    }

    public boolean hasLeftOrDefaultMappingsFor(AsOfAttribute[] leftAsOfAttributes)
    {
        int count = 0;
        for(int i=0;i<leftAsOfAttributes.length;i++)
        {
            if (leftAsOfAttributes[i].equals(this.getLeft())) count++;
            else if (leftAsOfAttributes[i].getDefaultDate() != null) count++;
        }
        return leftAsOfAttributes.length == count;
    }

    public boolean hasLeftMappingsFor(AsOfAttribute[] leftAsOfAttributes)
    {
        int count = 0;
        for(int i=0;i<leftAsOfAttributes.length;i++)
        {
            if (leftAsOfAttributes[i].equals(this.getLeft())) count++;
        }
        return leftAsOfAttributes.length == count;
    }

    public Attribute getAnyRightAttribute()
    {
        return this.right;
    }

    public Attribute getAnyLeftAttribute()
    {
        return this.left;
    }

    public String getResultOwnerClassName()
    {
        return this.left.zGetTopOwnerClassName();
    }

    public Set<Attribute> getAllLeftAttributes()
    {
        UnifiedSet set = new UnifiedSet(1); // this has to be a modifiable set.
        set.add(this.left);
        return set;
    }

    public Extractor[] getLeftAttributesWithoutFilters()
    {
        Extractor[] result = new Extractor[1];
        result[0] = this.left;
        return result;
    }

    public AsOfEqOperation[] getDefaultAsOfOperation(List<AsOfAttribute> ignore)
    {
        AsOfAttribute[] asOfAttributes = ((PrivateReladomoClassMetaData)getFromPortal().getClassMetaData()).getCachedAsOfAttributes();
        InternalList results = null;
        if (asOfAttributes != null)
        {
            for (int i = 0; i < asOfAttributes.length; i++)
            {
                if (!ignore.contains(asOfAttributes[i]) && !asOfAttributes[i].equals(this.right) && asOfAttributes[i].getDefaultDate() != null)
                {
                    if (results == null) results = new InternalList(2);
                    results.add(asOfAttributes[i].eq(asOfAttributes[i].getDefaultDate()));
                }
            }
        }
        if (results == null) return null;
        AsOfEqOperation[] arrayResults = new AsOfEqOperation[results.size()];
        results.toArray(arrayResults);
        return arrayResults;
    }

    public Mapper insertAsOfOperationInMiddle(AtomicOperation[] asOfEqOperation, MapperStackImpl insertPosition, AsOfEqualityChecker stack)
    {
        if (insertPosition.equals(stack.getCurrentMapperList()))
        {
            FilteredMapper mapper = new FilteredMapper(this, MultiEqualityOperation.createEqOperation(asOfEqOperation), null);
            mapper.setName(this.getRawName());
            return mapper;
        }
        Mapper mapper = insertAsOfOperationInMiddleForLeft(asOfEqOperation, insertPosition, stack);
        if (mapper != null)
        {
            mapper.setName(this.getRawName());
            return mapper;
        }
        if (this.isRightMapped)
        {
            mapper = insertAsOfOperationInMiddleForRight(asOfEqOperation, insertPosition, stack);
        }
        if (mapper == null)
        {
            stack.pushMapper(this);
            if (insertPosition.equals(stack.getCurrentMapperList()))
            {
                stack.popMapper();
                mapper = new FilteredMapper(this, null, MultiEqualityOperation.createEqOperation(asOfEqOperation));
                mapper.setName(this.getRawName());
                return mapper;
            }
            stack.popMapper();
        }
        if (mapper != null) mapper.setName(this.getRawName());
        return mapper;
    }

    public Mapper insertOperationInMiddle(MapperStack insertPosition, InternalList toInsert, TransitivePropagator transitivePropagator)
    {
        transitivePropagator.pushMapper(this);
        if (insertPosition.equals(transitivePropagator.getCurrentMapperList()))
        {
            transitivePropagator.popMapper();
            FilteredMapper mapper = new FilteredMapper(this, null, constructAndOperation(toInsert));
            mapper.setName(this.getRawName());
            return mapper;
        }
        transitivePropagator.popMapper();
        return null;
    }

    public Mapper insertAsOfOperationInMiddleForLeft(AtomicOperation[] asOfEqOperation, MapperStackImpl insertPosition, AsOfEqualityChecker stack)
    {
        if (!isLeftMapped) return null;
        MappedAttribute mapped = (MappedAttribute) left;
        Mapper mapper = mapped.getMapper();
        Mapper newMapper = mapper.insertAsOfOperationInMiddle(asOfEqOperation, insertPosition, stack);
        if (newMapper != null)
        {
            Attribute newLeft = (Attribute) mapped.cloneForNewMapper(newMapper, mapped.getParentSelector());
            return newLeft.constructEqualityMapper(right);
        }
        return null;
    }

    public Mapper insertAsOfOperationInMiddleForRight(AtomicOperation[] asOfEqOperation, MapperStackImpl insertPosition, AsOfEqualityChecker stack)
    {
        if (!isRightMapped) return null;
        MappedAttribute mapped = (MappedAttribute) right;
        Mapper mapper = mapped.getMapper();
        Mapper newMapper = mapper.insertAsOfOperationInMiddle(asOfEqOperation, insertPosition, stack);
        if (newMapper != null)
        {
            Attribute newRight = (Attribute) mapped.cloneForNewMapper(newMapper, mapped.getParentSelector());
            return left.constructEqualityMapper(newRight);
        }
        return null;
    }

    public Operation getOperationFromResult(Object result, Map<Attribute, Object> tempOperationPool)
    {
        return right.zGetOperationFromResult(result, tempOperationPool);
    }

    public Operation getOperationFromOriginal(Object original, Map<Attribute, Object> tempOperationPool)
    {
        return right.zGetOperationFromOriginal(original, left, tempOperationPool);
    }

    @Override
    public Operation getPrototypeOperation(Map<Attribute, Object> tempOperationPool)
    {
        return right.zGetPrototypeOperation(tempOperationPool);
    }

    public Object createOperationOrMapperForTempJoin(Map<Attribute, Attribute> attributeMap, Object prototypeObject)
    {
        Attribute newLeft = attributeMap.get(this.left);
        if (newLeft == null)
        {
            Object constValue = this.left.valueOf(prototypeObject);
            return this.right.nonPrimitiveEq(constValue);
        }
        return createMapperForTempJoin(attributeMap, prototypeObject, 0);
    }

    public Mapper createMapperForTempJoin(Map<Attribute, Attribute> attributeMap, Object prototypeObject, int chainPosition)
    {
        Attribute newLeft = attributeMap.get(this.left);
        if (newLeft == null)
        {
            throw new RuntimeException("could not create mapper for temp join. Looking for attribute "+this.left.getAttributeName());
        }
        return substituteNewLeft(newLeft);
    }

    public void appendSyntheticName(StringBuilder stringBuilder)
    {
        stringBuilder.append("[ -> ");
        stringBuilder.append(this.getFromPortal().getBusinessClassName()).append(": ");
        appendEquality(stringBuilder);
        stringBuilder.append("]");
    }

    public boolean isSingleLevelJoin()
    {
        return !this.isLeftMapped && !this.isRightMapped;
    }

    @Override
    public boolean isEstimatable()
    {
        return this.getLeft().getOwnerPortal().isFullyCached() && !this.getLeft().getOwnerPortal().isForTempObject()
                && this.getRight().getOwnerPortal().isFullyCached() && !this.getRight().getOwnerPortal().isForTempObject();
    }

    protected void appendEquality(StringBuilder stringBuilder)
    {
        stringBuilder.append(this.left.getAttributeName()).append(" = ").append(this.right.getAttributeName());
    }

    protected Mapper substituteNewLeft(Attribute newLeft)
    {
        return new EqualityMapper(newLeft, right);
    }

    public boolean isMappableForTempJoin(Set<Attribute> attributeMap)
    {
        return attributeMap.contains(this.left);
    }

    @Override
    public void clearLeftOverFromObjectCache(Collection<Object> parentObjects, EqualityOperation extraEqOp, Operation extraOperation)
    {
        MithraFastList attrList = new MithraFastList(2);
        attrList.add(this.getRight());
        if (extraEqOp != null)
        {
            extraEqOp.addEqAttributes(attrList);
        }
        Cache cache = ((Attribute)attrList.get(0)).getOwnerPortal().getCache();
        IndexReference bestIndexReference = cache.getBestIndexReference(attrList);
        if (bestIndexReference != null && bestIndexReference.isValid() && cache.isUnique(bestIndexReference.indexReference))
        {
            Attribute[] indexAttributes = cache.getIndexAttributes(bestIndexReference.indexReference);
            List<Extractor> extractors = new MithraFastList<Extractor>(indexAttributes.length);
            Operation localExtraOperation = extraOperation;
            if (indexAttributes.length != attrList.size())
            {
                localExtraOperation = localExtraOperation == null ? extraEqOp : extraEqOp.and(localExtraOperation);
            }
            for (Attribute a : indexAttributes)
            {
                if (a.equals(this.getRight()))
                {
                    extractors.add(this.getLeft());
                }
                else
                {
                    extractors.add(extraEqOp.getParameterExtractorFor(a));
                }
            }
            cache.markNonExistent(bestIndexReference.indexReference, parentObjects, extractors, null, localExtraOperation);
        }
    }
}

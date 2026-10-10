const {test} = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const source = fs.readFileSync(require('node:path').join(__dirname, '../app.js'), 'utf8');
const context = vm.createContext({});
vm.runInContext(source.slice(source.indexOf('function destinationCategoryLeaves('), source.indexOf('function crossPlatformPublishShops()')), context);
const match = (capture, leaves, saved) => context.matchDestinationCategory(capture, leaves, saved);
const leaf = (id, label) => ({id, label});
// Official MY v2 response, 2026-09-10: retain the complete taxonomy so competing branches remain in the regression.
const officialMyCategories = require('./fixtures/tiktok-my-v2-categories-20260910.json');
const actual96SkuFacts = require('./fixtures/shopee-my-96sku-category-facts.json');
const crossBorderCategories = require('./fixtures/tiktok-my-cross-border-categories-20260910.json');
const officialMyLeaves = context.destinationCategoryLeaves(officialMyCategories, 'TIKTOK_SHOP');
const shopeeOptions = {categories: {category_list: [
  {category_id: 10, display_category_name: 'Women Clothes', has_children: true},
  {category_id: 11, parent_category_id: 10, display_category_name: 'Tops', has_children: true},
  {category_id: 12, parent_category_id: 11, display_category_name: 'T-shirts', has_children: false},
  {category_id: 13, parent_category_id: 11, display_category_name: 'Shirts & Blouses', has_children: false},
  {category_id: 20, display_category_name: 'Men Clothes', has_children: true},
  {category_id: 21, parent_category_id: 20, display_category_name: 'T-shirts', has_children: false},
  {category_id: 30, display_category_name: 'Mobile & Gadgets', has_children: true},
  {category_id: 31, parent_category_id: 30, display_category_name: 'Phone Cases', has_children: false}
]}};
const tiktokOptions = {categories: {categories: [
  {id: 'a', name: 'Womenswear & Underwear', local_name: '女装与女士内衣', is_leaf: false},
  {id: 'b', parent_id: 'a', name: 'Womens Tops', local_name: '女士上装', is_leaf: false},
  {id: 'c', parent_id: 'b', name: 'Womens T-shirts', local_name: '女士短袖', is_leaf: true},
  {id: 'd', parent_id: 'b', name: 'Womens Shirts', local_name: '女士衬衫', is_leaf: true},
  {id: 'e', name: 'Menswear', local_name: '男装', is_leaf: false},
  {id: 'f', parent_id: 'e', name: 'Mens T-shirts', local_name: '男士短袖', is_leaf: true}
]}};
test('actual Chinese TikTok category maps to Shopee women T-shirts, not the source ID or shirts', () => {
  const capture = {sourcePlatform: 'TIKTOK_SHOP', categoryId: '31', categoryName: '女装与女士内衣 > 女士上装 > 女士短袖',
    title: "【Ready Stock】Cherry Women's White T-Shirt/Short Sleeve Round Neck", attributes: {'Panjang Lengan Baju': 'Lengan pendek'}};
  assert.equal(context.resolveDestinationPublishOptions('SHOPEE', capture, shopeeOptions).categoryId, '12');
});
test('English Shopee taxonomy maps to TikTok Chinese display and official English aliases', () => {
  assert.equal(context.resolveDestinationPublishOptions('TIKTOK_SHOP',
    {sourcePlatform: 'SHOPEE', categoryName: 'Women Clothes > Tops > T-shirts'}, tiktokOptions).categoryId, 'c');
});
test('Malay Tshirt aliases work in both directions', () => {
  for (const categoryName of ['Pakaian Wanita > Atasan > Kemeja-T', 'Pakaian Wanita > Baju T', 'Pakaian Wanita > Kaos']) {
    assert.equal(context.resolveDestinationPublishOptions('TIKTOK_SHOP', {categoryName}, tiktokOptions).categoryId, 'c', categoryName);
    assert.equal(context.resolveDestinationPublishOptions('SHOPEE', {categoryName}, shopeeOptions).categoryId, '12', categoryName);
  }
  assert.equal(match({categoryName: '女装 > 女士短袖'}, [leaf('1', 'Pakaian Lelaki > Kemeja-T'), leaf('2', 'Pakaian Wanita > Kemeja-T')]), '2');
});
test('title provides concrete type when source leaf is broad, attributes distinguish gender and sleeve length', () => {
  const options = [leaf('1', 'Women Clothes > Long Sleeve T-shirts'), leaf('2', 'Women Clothes > Short Sleeve T-shirts'), leaf('3', 'Men Clothes > Short Sleeve T-shirts')];
  assert.equal(match({categoryName: 'Tops', title: 'Cotton T-Shirt', attributes: {Gender: 'Female', 'Sleeve length': 'Short sleeve'}}, options), '2');
  assert.equal(match({categoryName: '上装', title: 'Cotton T-Shirt', normalizedData: {attributes: [{name: '性别', value: '女士'}, {name: '袖长', value: '短袖'}]}}, options), '2');
});
test('more suitable concrete category wins over broad Tops', () => {
  const capture = {categoryName: '女装 > 短袖', title: 'Women T-shirt'};
  assert.equal(match(capture, [leaf('1', 'Women Clothes > Tops'), leaf('2', 'Women Clothes > T-shirts')]), '2');
  assert.equal(match(capture, [leaf('1', 'Women Clothes > Tops')]), '1');
});
test('an ambiguous broad source cannot invent a specific type without title or attribute evidence', () => {
  assert.equal(match({categoryName: 'Women Clothes > Tops'}, [leaf('1', 'Women > T-shirts'), leaf('2', 'Women > Blouses')]), '');
  assert.equal(match({categoryName: 'Women Fashion', title: 'Ready stock cotton white summer new'}, [leaf('1', 'Women > T-shirts')]), '');
});
test('identical leaves are disambiguated by source path, never by list order', () => {
  const categories = [leaf('1', 'Men > Shirts'), leaf('2', 'Women > Shirts')];
  assert.equal(match({categoryName: 'Source > Shirts'}, categories), '');
  assert.equal(match({categoryName: '女士上装 > 衬衫'}, categories), '2');
  assert.equal(match({categoryName: '女士上装 > 衬衫'}, [...categories].reverse()), '2');
});
test('unknown exact leaf names remain supported but nearby unsupported categories stay ambiguous', () => {
  assert.equal(match({categoryName: 'Source > Tripods'}, [leaf('1', 'Camera Accessories > Tripods')]), '1');
  assert.equal(match({categoryName: 'Source > Tripods'}, [leaf('1', 'Camera > Tripods'), leaf('2', 'Mobile > Tripods')]), '');
});
test('clothing cannot match unrelated electronics through gender, material or source IDs', () => {
  assert.equal(match({categoryId: '1', categoryName: '女装 > 短袖', title: 'Soft White Cotton T-shirt'},
    [leaf('1', 'Electronics > White Phone Cases for Women'), leaf('2', 'Women > Cotton Socks')]), '');
  assert.equal(match({categoryName: '手机配件 > 手机壳', title: 'Phone case with women T-shirt print'},
    [leaf('1', 'Women > T-shirts'), leaf('2', 'Mobile > Phone Cases')]), '2');
});
test('child and adult leaves are separated even when type and gender match', () => {
  const options = [leaf('1', 'Women Clothes > T-shirts'), leaf('2', 'Kids Fashion > Girls T-shirts')];
  assert.equal(match({categoryName: '童装 > 女童短袖'}, options), '2');
  assert.equal(match({categoryName: '女装 > 女士短袖'}, options), '1');
  assert.equal(match({categoryName: '男装 > 男士短袖'}, options), '');
});
test('explicit sleeve conflicts and incompatible concrete garment types are rejected', () => {
  assert.equal(match({categoryName: '女士短袖'}, [leaf('1', 'Women > Long Sleeve T-shirts'), leaf('2', 'Women > Short Sleeve Shirts')]), '');
  assert.equal(match({categoryName: 'Women > Dresses'}, [leaf('1', 'Women > Skirts')]), '');
});
test('Chinese and Malay non-shirt categories use the same bidirectional matcher', () => {
  assert.equal(match({categoryName: '女士服装 > 连衣裙'}, [leaf('1', 'Pakaian Wanita > Gaun'), leaf('2', 'Pakaian Wanita > Skirt')]), '1');
  assert.equal(match({categoryName: 'Pakaian Wanita > Seluar Jeans'}, [leaf('1', 'Women > Jeans'), leaf('2', 'Women > Trousers')]), '1');
  assert.equal(match({categoryName: '电子产品 > 耳机'}, [leaf('1', 'Electronics > Fon Telinga'), leaf('2', 'Electronics > Pengecas')]), '1');
});
test('official aliases can match unsupported local language while keeping user-facing labels', () => {
  const result = context.resolveShopeePublishOptions({categoryName: 'Women Clothes > T-shirts'}, {categories: {category_list: [
    {category_id: 1, display_category_name: 'เสื้อผ้าผู้หญิง', original_category_name: 'Women Clothes', has_children: true},
    {category_id: 2, parent_category_id: 1, display_category_name: 'เสื้อยืด', original_category_name: 'T-shirts', has_children: false}
  ]}});
  assert.equal(result.categoryId, '2');
  assert.equal(result.leaves[0].label, 'เสื้อผ้าผู้หญิง > เสื้อยืด');
});
test('only official leaves may be selected, including remembered choices and inconsistent parent flags', () => {
  for (const platform of ['SHOPEE', 'TIKTOK_SHOP']) {
    const options = platform === 'SHOPEE' ? shopeeOptions : tiktokOptions;
    const parent = platform === 'SHOPEE' ? '11' : 'b';
    const expected = platform === 'SHOPEE' ? '12' : 'c';
    const result = context.resolveDestinationPublishOptions(platform, {categoryName: '女装 > 女士短袖'}, options, {}, {category: parent});
    assert.equal(result.categoryId, expected);
    assert.ok(!result.leaves.some(c => c.id === parent));
  }
  const leaves = context.destinationCategoryLeaves([
    {category_id: 1, display_category_name: 'T-shirts', has_children: false},
    {category_id: 2, parent_category_id: 1, display_category_name: 'Short Sleeve T-shirts', has_children: false}
  ], 'SHOPEE');
  assert.deepEqual(Array.from(leaves, c => c.id), ['2']);
});
test('compatible preferences resolve close matches, while stronger semantic or exact matches take precedence', () => {
  const options = [leaf('1', 'Women > T-shirts'), leaf('2', 'Women > Tops')];
  assert.equal(match({categoryName: '女装 > 女士短袖'}, options, {category: '2'}), '1');
  assert.equal(match({categoryName: '女装 > 女士短袖'}, options, {category: 'deleted'}), '1');
  assert.equal(match({categoryName: 'Women > T-shirts'}, options, {category: '2'}), '1');
  const closeOptions = [leaf('1', 'Women > T-shirts'), leaf('2', 'Fashion > Women > T-shirts')];
  assert.equal(match({categoryName: '女装 > 女士短袖'}, closeOptions), '');
  assert.equal(match({categoryName: '女装 > 女士短袖'}, closeOptions, {category: '2'}), '2');
});

test('ordinary apparel avoids specialized maternity, sports and pet branches; matching context selects them', () => {
  const options = [leaf('1', 'Women Clothes > T-shirts'), leaf('2', 'Women Maternity > T-shirts'), leaf('3', 'Women Sportswear > T-shirts'), leaf('4', 'Pet Clothing > T-shirts')];
  assert.equal(match({categoryName: '女装 > 女士短袖'}, options), '1');
  assert.equal(match({categoryName: '女装 > 女士短袖', title: '孕妇宽松T恤'}, options), '2');
  assert.equal(match({categoryName: '女装 > 女士短袖', title: 'Yoga sports T-shirt'}, options), '3');
  assert.equal(match({categoryName: '女装 > 女士短袖'}, [options[3]]), '');
});
test('a combined Shirts & T-shirts category remains eligible but a T-shirt Dress is a different product', () => {
  assert.equal(match({categoryName: '女装 > 女士短袖'}, [leaf('1', 'Women > Shirts & T-shirts')]), '1');
  assert.equal(match({categoryName: '女装 > 女士短袖'}, [leaf('1', 'Women > T-shirt Dresses')]), '');
});
test('generic Accessories and Other leaves cannot create arbitrary cross-platform matches', () => {
  assert.equal(match({categoryName: 'Home > Accessories'}, [leaf('1', 'Electronics > Accessories')]), '');
  assert.equal(match({categoryName: 'Source > Other'}, [leaf('1', 'Women Clothes > Other')]), '');
});

test('actual 96-SKU garment maps to ordinary T-shirts across the entire MY taxonomy using explicit sleeve and collar facts', () => {
  assert.equal(officialMyCategories.length, 2746);
  assert.match(officialMyLeaves.find(c => c.id === '862096').label, /Pre-Owned/);
  assert.equal(match(actual96SkuFacts, officialMyLeaves), '601302');
  assert.equal(match(actual96SkuFacts, [...officialMyLeaves].reverse()), '601302');
  assert.equal(match(actual96SkuFacts, officialMyLeaves, {category: '862096'}), '601302');
  assert.equal(match(actual96SkuFacts, officialMyLeaves, {category: '843400'}), '601302');
  const beforeUserConfirmedNew = structuredClone(actual96SkuFacts);
  delete beforeUserConfirmedNew.normalizedData.condition;
  delete beforeUserConfirmedNew.normalizedData.attributes['商品成色'];
  assert.equal(match(beforeUserConfirmedNew, officialMyLeaves), '601302');
});

test('tanks and camisoles are real sleeveless product types when source specifications do not contradict them', () => {
  for (const categoryName of ['Women Clothes > Tops > Tanks & Camisoles', 'Women > Tank Tops', '女装 > 吊带背心']) {
    assert.equal(match({categoryName, normalizedData: {condition: 'NEW'}}, officialMyLeaves), '843400', categoryName);
  }
  const noSleeveEvidence = {...actual96SkuFacts, description: 'Everyday top. Recommended with a short sleeve T-shirt.'};
  assert.equal(match(noSleeveEvidence, officialMyLeaves), '843400');
});

test('new and unknown-condition goods never inherit pre-owned, used or second-hand branches, including saved or exact paths', () => {
  for (const prefix of ['Pre-Owned', 'Used', 'Second Hand', '二手']) {
    const candidates = [leaf('used', `${prefix} > Womenswear > Women's Tops`), leaf('new', "Womenswear > Women's Tops")];
    for (const normalizedData of [{}, {condition: 'NEW'}, {attributes: {'商品成色': '全新'}}]) {
      assert.equal(match({categoryName: 'Women > Tops', normalizedData}, candidates, {category: 'used'}), 'new');
      assert.equal(match({categoryName: 'Women > Tops', normalizedData}, [candidates[0]]), '');
    }
    assert.equal(match({categoryName: candidates[0].label, normalizedData: {condition: 'NEW'}}, candidates), 'new');
  }
  assert.equal(match({categoryName: 'Women > Tops', normalizedData: {condition: 'USED'}}, officialMyLeaves), '862096');
});

test('exact labelled garment specifications can correct tanks but never override phone-case or unrelated garment types', () => {
  const options = [leaf('tee', 'Women > T-shirts'), leaf('tank', 'Women > Tanks & Camisoles'), leaf('phone', 'Mobile > Phone Cases')];
  const specifications = 'Sleeve length: short sleeved Collar: Round neck';
  assert.equal(match({categoryName: 'Women > Tanks & Camisoles', description: specifications}, options), 'tee');
  assert.equal(match({categoryName: 'Women > Tanks & Camisoles', attributes: {'袖长': '短袖', '领口': '圆领'}}, options), 'tee');
  assert.equal(match({categoryName: 'Mobile > Phone Cases', title: 'Phone case with women T-shirt print', description: specifications}, options), 'phone');
  assert.equal(match({categoryName: 'Women > Tanks & Camisoles', title: 'Short sleeve T-shirt print', description: 'Looks great with round neck short sleeved tops'}, options), 'tank');
  assert.equal(match({categoryName: 'Women > Blouses', description: specifications}, [leaf('blouse', 'Women > Blouses'), ...options]), 'blouse');
});

test('contradictory explicit sleeve, collar or condition facts stop selection instead of reusing a remembered category', () => {
  const capture = {...actual96SkuFacts, attributes: {'Sleeve length': 'Sleeveless'}};
  assert.equal(match(capture, officialMyLeaves, {category: '601302'}), '');
  assert.equal(match({...actual96SkuFacts, attributes: {'Collar': 'V-neck'}}, officialMyLeaves, {category: '601302'}), '');
  const mixedCondition = {...actual96SkuFacts, condition: 'NEW', normalizedData: {condition: 'USED'}};
  assert.equal(match(mixedCondition, officialMyLeaves, {category: '862096'}), '');
});

test('actual cross-border shop matches the correct garment before reporting its category permission restriction', () => {
  assert.equal(crossBorderCategories.totalCategories, 2444);
  assert.deepEqual(crossBorderCategories.permissionCounts, {AVAILABLE: 144, NON_MAIN_CATEGORY: 2300});
  const leaves = context.destinationCategoryLeaves(crossBorderCategories.categories, 'TIKTOK_SHOP');
  const garment = leaves.find(c => c.id === '601302');
  assert.deepEqual(Array.from(garment.permissionStatuses), ['NON_MAIN_CATEGORY']);
  assert.equal(garment.permissionPath.length, 3);
  for (const saved of [{}, {category: '600321'}, {category: '600017'}]) {
    assert.equal(match(actual96SkuFacts, leaves, saved), '601302');
    assert.throws(() => context.resolveTikTokPublishOptions(actual96SkuFacts,
      {categories: {categories: crossBorderCategories.categories}}, {}, saved), error => {
      assert.equal(error.categoryPermissionBlocked, true);
      assert.match(error.message, /Women's T-shirts.*601302.*主营类目.*更换/);
      return true;
    });
  }
  const available = leaves.filter(c => c.permissionStatuses.includes('AVAILABLE'));
  assert.equal(match(actual96SkuFacts, available, {category: '600321'}), '');
});

test('explicit ancestor denial or mixed permission statuses block a leaf while available household products can match', () => {
  const categories = structuredClone(crossBorderCategories.categories);
  categories.find(c => c.id === '601302').permission_statuses = ['AVAILABLE'];
  assert.throws(() => context.resolveTikTokPublishOptions(actual96SkuFacts, {categories: {categories}}), /主营类目/);
  for (const category of categories) category.permission_statuses = ['AVAILABLE'];
  assert.equal(context.resolveTikTokPublishOptions(actual96SkuFacts, {categories: {categories}}).categoryId, '601302');
  categories.find(c => c.id === '601302').permission_statuses = ['AVAILABLE', 'INVITE_ONLY'];
  assert.throws(() => context.resolveTikTokPublishOptions(actual96SkuFacts, {categories: {categories}}), /INVITE_ONLY/);
  assert.equal(context.resolveTikTokPublishOptions({categoryName: 'Home Supplies > Home Decor > Clocks'},
    {categories: {categories: crossBorderCategories.categories}}).categoryId, '600321');
});

test('explicit ladies garment title corrects a conflicting source mens taxonomy and old preference', () => {
  const capture = require('./fixtures/shopee-ladies-mens-category-facts.json');
  assert.equal(match(capture, officialMyLeaves), '601302');
  assert.equal(match(capture, officialMyLeaves, {category:'601226'}), '601302');
  assert.equal(match({...capture, categoryName:'Women Clothes > Tops > T-Shirts', title:'Mens short sleeve T-shirt'}, officialMyLeaves), '601226');
});
test('gender correction cannot follow incidental titles or override conflicting explicit attributes', () => {
  assert.equal(match({categoryName:'Men Clothes > Tops > T-Shirts', title:'Ladies summer sale'}, officialMyLeaves), '601226');
  assert.equal(match({categoryName:'Men Clothes > Tops > T-Shirts', title:'Ladies T-shirt', attributes:{Gender:'Male'}}, officialMyLeaves), '');
});

test('VN authorized womens apparel is used automatically for equivalent mens T-shirts', () => {
  const categories = require('./fixtures/tiktok-vn-gender-categories-20260910.json');
  const capture = {categoryName:'Men Clothes > Tops > T-Shirts', title:'Mens cotton T-shirt'};
  const result = context.resolveTikTokPublishOptions(capture, {categories});
  assert.equal(result.categoryId, '601302');
  assert.equal(result.genderConverted, true);
  const actual = require('./fixtures/shopee-ladies-mens-category-facts.json');
  assert.equal(context.resolveTikTokPublishOptions(actual, {categories}, {}, {category:'601226'}).categoryId, '601302');
  const reversed = structuredClone(categories);
  for (const c of reversed.categories) c.permission_statuses = c.permission_statuses.includes('AVAILABLE') ? ['NON_MAIN_CATEGORY'] : ['AVAILABLE'];
  assert.equal(context.resolveTikTokPublishOptions({categoryName:'Women Clothes > Tops > T-shirts',title:'Womens T-shirt'}, {categories:reversed}).categoryId, '601226');
  assert.equal(context.resolveTikTokPublishOptions({categoryName:'Men Clothes > Tops > Shirts',title:'Mens Shirt'}, {categories}).categoryId, '');
});

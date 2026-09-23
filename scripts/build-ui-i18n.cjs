/* Generate the synchronous, cache-versioned UI catalog from reviewed translations. */
const fs=require('node:fs'),path=require('node:path');
const root=path.resolve(__dirname,'../frontend/i18n');
const catalog=JSON.parse(fs.readFileSync(path.join(root,'catalog.json'),'utf8'));
Object.assign(catalog,JSON.parse(fs.readFileSync(path.join(root,'overrides.json'),'utf8')));
for(const [key,values]of Object.entries(catalog)){
 if(values.length!==6||values.some(v=>typeof v!=='string'||!v.trim()))throw Error('Incomplete translations: '+key);
 const slots=JSON.stringify((key.match(/\{\d+\}/g)||[]).sort());
 for(const value of values)if(JSON.stringify((value.match(/\{\d+\}/g)||[]).sort())!==slots)throw Error('Invalid placeholders: '+key);
}
const json=JSON.stringify(catalog);
fs.writeFileSync(path.join(root,'catalog.json'),json+'\n');
fs.writeFileSync(path.join(root,'catalog.js'),'/* UI catalog: en, id, th, vi, ms, fil. */\nwindow.HoyeeCatalog='+json+';\nwindow.HoyeeCatalogSlots='+fs.readFileSync(path.join(root,'slots.json'),'utf8')+';\n');
console.log(`Built ${Object.keys(catalog).length} UI translations for six languages.`);

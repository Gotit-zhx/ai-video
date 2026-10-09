INSERT IGNORE INTO permissions(id,code,name,module,resource_type,description,sort_order)
VALUES(UUID(),'hr:access','全部人力资源功能','HR','MENU','招聘、简历、人才、入职、话术、部门及 BI；业务数据按用户独立隔离',710);

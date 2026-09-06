#!/usr/bin/env python3
"""Local Docker managed View integration. Requires RTDWH_ADMIN_PASSWORD.
Creates two isolated Paimon tables, scoped users, ordinary/nested Views and consumers.
--keep retains exact fixtures for UI inspection; --cleanup removes recorded fixtures.
No credentials are written to the fixture state or printed.
"""
import json, os, secrets, subprocess, sys, time
from pathlib import Path
from urllib.request import Request, urlopen
from urllib.error import HTTPError
BASE='http://127.0.0.1:8080'
GW='http://127.0.0.1:9083'
STATE=Path('tmp/view-smoke-state.json')
admin=''; session=None
state={'views':[], 'tables':[], 'roles':[], 'users':[], 'services':[], 'reports':[], 'apps':[]}
def api(method,path,body=None,token=None,reject=None,headers=None):
 h={'Content-Type':'application/json','Authorization':'Bearer '+(admin if token is None else token)}
 h.update(headers or {})
 req=Request(BASE+path,method=method,data=None if body is None else json.dumps(body).encode(),headers=h)
 try:
  with urlopen(req,timeout=100) as r: result=json.load(r)
 except HTTPError as e:
  result=json.load(e)
  if reject and e.code in (400,403) and any(w in result.get('message','') for w in reject):return None
  raise RuntimeError(f'{method} {path}: {e.code} {result.get("message")}') from None
 if reject:raise AssertionError('Expected rejection: '+path)
 assert result.get('code')==0,result.get('message')
 return result.get('data')
def db(sql):
 r=subprocess.run(['docker','exec','-i','rtdwh-mysql','sh','-c','MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql -uroot -N -B rtdwh_mgmt'],input=sql,text=True,capture_output=True,check=True)
 return r.stdout.strip()
def doris(sql):
 subprocess.run(['docker','exec','-i','rtdwh-mysql','mysql','-hrtdwh-doris-fe','-P9030','-uroot'],input=sql,text=True,capture_output=True,check=True)
def gw(method,path,body=None):
 with urlopen(Request(GW+path,method=method,data=None if body is None else json.dumps(body).encode(),headers={'Content-Type':'application/json'}),timeout=100) as r:return json.load(r)
def execute(sql):
 handle=gw('POST',f'/v1/sessions/{session}/statements',{'statement':sql,'executionConfig':{'execution.runtime-mode':'batch','parallelism.default':'1'}})['operationHandle']
 for _ in range(300):
  status=gw('GET',f'/v1/sessions/{session}/operations/{handle}/status')['status']
  if status=='FINISHED':return
  assert status not in ('ERROR','CANCELED','CLOSED'),'Gateway operation failed '+handle
  time.sleep(.3)
 raise AssertionError('Gateway timeout '+handle)
def initialize():
 global session
 env=dict(v.split('=',1) for v in json.loads(subprocess.check_output(['docker','inspect','rtdwh-backend'],text=True))[0]['Config']['Env'] if '=' in v)
 session=gw('POST','/v1/sessions',{})['sessionHandle']
 lit=lambda s:"'"+s.replace("'","''")+"'"
 opts={'type':'paimon','metastore':'jdbc','uri':env['PAIMON_JDBC_URI'],'jdbc.user':env['PAIMON_JDBC_USER'],'jdbc.password':env['PAIMON_JDBC_PASSWORD'],'catalog-key':env.get('PAIMON_CATALOG_KEY','rtdwh'),'warehouse':env['PAIMON_WAREHOUSE']}
 execute('CREATE CATALOG rtdwh_paimon WITH ('+','.join(lit(k)+'='+lit(v) for k,v in opts.items())+')')
 execute('USE CATALOG rtdwh_paimon')
def save():
 STATE.parent.mkdir(parents=True,exist_ok=True)
 STATE.write_text(json.dumps(state,indent=2));STATE.chmod(0o600)
def cleanup():
 # Exact fixture ids and identifier-only names; physical Views before Paimon tables.
 for item in reversed(state['views']):
  assert item['name'].startswith('smoke_view_') and item['name'].replace('_','').isalnum()
  doris('DROP VIEW IF EXISTS internal.rtdwh_views.'+item['name'])
 for name in state['tables']:
  assert name.startswith('smoke_view_') and name.replace('_','').isalnum()
  execute('DROP TABLE IF EXISTS ods.'+name)
 for sid in state['services']:
  db(f'DELETE FROM data_service_invocation_log WHERE service_id={int(sid)}; DELETE FROM data_service_version WHERE service_id={int(sid)}; DELETE FROM data_service_grant WHERE service_id={int(sid)}; DELETE FROM data_service_definition WHERE id={int(sid)};')
 for aid in state['apps']:db(f'DELETE FROM data_service_app WHERE id={int(aid)};')
 for rid in state['reports']:db(f'DELETE FROM report_run WHERE report_id={int(rid)}; DELETE FROM report_template WHERE id={int(rid)};')
 for uid in state['users']:db(f'DELETE FROM query_history WHERE user_id={int(uid)}; DELETE FROM sys_user_role WHERE user_id={int(uid)}; DELETE FROM sys_user WHERE id={int(uid)};')
 for rid in state['roles']:api('DELETE','/admin/roles/'+str(rid))
 for v in state['views']:
  aid=int(v['tableId']);vid=int(v['id'])
  db(f'DELETE FROM managed_view_version WHERE view_id={vid}; DELETE FROM managed_view WHERE id={vid}; DELETE FROM dwh_table_meta WHERE id={aid};')
 for name in state['tables']:
  aid=db("SELECT id FROM dwh_table_meta WHERE catalog_name='rtdwh_paimon' AND paimon_db='ods' AND paimon_table='"+name+"';")
  if aid:
   aid=int(aid);db(f'DELETE FROM dwh_column_meta WHERE table_meta_id={aid}; DELETE FROM dwh_schema_history WHERE table_meta_id={aid}; DELETE FROM dwh_table_meta WHERE id={aid};')
 STATE.unlink(missing_ok=True)
def create_view(name,sql,token):
 v=api('POST','/dwh/views',{'name':name,'sql':sql,'description':'V2-05 本地验收样例'},token)
 state['views'].append({'id':v['definition']['id'],'name':name,'assetId':v['asset']['assetId'],'tableId':v['asset']['id']});save()
 return v
def publish(v,token):
 path='/dwh/views/'+v['asset']['assetId']
 assert api('POST',path+'/preview',token=token)['publishable']
 result=api('POST',path+'/publish',{'expectedVersion':v['definition']['version']},token)
 assert result['definition']['operationState']=='idle',result['definition'].get('lastError')
 return result
def query(name,token):
 result=api('POST','/query/execute',{'sql':'SELECT amount FROM internal.rtdwh_views.'+name,'catalog':'rtdwh_paimon','database':'ods'},token)
 assert result['status']=='success',result.get('errorMsg')
 return result
try:
 admin=api('POST','/auth/login',{'username':'admin','password':os.environ['RTDWH_ADMIN_PASSWORD']},token='')['token']
 initialize()
 if '--cleanup' in sys.argv:
  state=json.loads(STATE.read_text());cleanup();print('PASS: exact View fixtures cleaned');sys.exit(0)
 if STATE.exists():raise RuntimeError('Existing View fixture state; use --cleanup first')
 prefix='smoke_view_'+str(int(time.time()))
 for suffix in ['a','b']:
  name=prefix+'_'+suffix;execute(f'CREATE TABLE ods.{name} (id BIGINT, amount BIGINT, PRIMARY KEY(id) NOT ENFORCED) WITH (\'bucket\'=\'1\')');state['tables'].append(name);save()
  execute(f'INSERT INTO ods.{name} VALUES (1,10),(2,20)')
 api('POST','/dwh/sync-metadata');doris('REFRESH CATALOG rtdwh_paimon')
 perms=api('GET','/admin/permissions')
 ids=[p['id'] for p in perms if p['permCode'] in ['dwh:view','dwh:manage','foundation:view','lineage:view','query:adhoc','report:view','report:create','data-service:view','data-service:manage']]
 actors=[]
 for suffix in ['a','b']:
  name=prefix+'_'+suffix
  scopes=[{'catalogPattern':'rtdwh_paimon','databasePattern':'ods','tablePattern':name}]
  scopes += [{'catalogPattern':'internal','databasePattern':'rtdwh_views','tablePattern':n} for n in [name,name+'_nested']]
  rolebody={'roleCode':name,'roleName':name,'permissionIds':ids,'dataScopes':scopes}
  role=api('POST','/admin/roles',rolebody);state['roles'].append(role['id']);save()
  password=secrets.token_urlsafe(24)
  user=api('POST','/admin/users',{'username':name,'password':password,'roleIds':[role['id']]});state['users'].append(user['id']);save()
  token=api('POST','/auth/login',{'username':name,'password':password},token='')['token']
  v=publish(create_view(name,f'SELECT SUM(amount) AS amount FROM rtdwh_paimon.ods.{name}',token),token)
  actors.append({'name':name,'token':token,'view':v,'role':role['id'],'body':rolebody})
 a,b=actors
 own=api('GET','/foundation/search?keyword='+a['name'],token=a['token'])
 assert any(str(x['id'])==str(a['view']['asset']['id']) and x['type']=='table' for x in own), 'View missing from scoped unified search'
 assert not api('GET','/foundation/search?keyword='+b['name'],token=a['token']), 'Foreign asset exposed in unified search'
 nested=publish(create_view(a['name']+'_nested','SELECT amount FROM internal.rtdwh_views.'+a['name'],a['token']),a['token'])
 assert query(a['name']+'_nested',a['token'])['rows']==[[30]]
 query(b['name'],b['token'])
 api('POST','/query/execute',{'sql':'SELECT * FROM internal.rtdwh_views.'+b['name']},a['token'],reject=['无权','权限'])
 api('POST','/dwh/views/'+a['view']['asset']['assetId']+'/publish',{'expectedVersion':0},a['token'],reject=['已被修改'])
 # A saved draft cannot change the executed publication.
 path='/dwh/views/'+a['view']['asset']['assetId']
 old=a['view'];v=api('PUT',path,{'sql':f"SELECT SUM(amount)+1 AS amount FROM rtdwh_paimon.ods.{a['name']}",'expectedVersion':old['definition']['version']},a['token'])
 assert query(a['name'],a['token'])['rows']==[[30]]
 v=publish(v,a['token']);assert query(a['name'],a['token'])['rows']==[[31]]
 assert query(a['name']+'_nested',a['token'])['rows']==[[31]]
 assert old['versions'][0]['sqlContent']==v['versions'][-1]['sqlContent']
 # Breaking output contract must be reviewable and blocked before any DDL.
 changed=api('PUT',path,{'sql':f"SELECT SUM(amount) AS renamed FROM rtdwh_paimon.ods.{a['name']}",'expectedVersion':v['definition']['version']},a['token'])
 assert not api('POST',path+'/preview',token=a['token'])['publishable']
 api('POST',path+'/publish',{'expectedVersion':changed['definition']['version']},a['token'],reject=['输出列契约'])
 api('PUT',path,{'sql':v['definition']['draftSql'],'expectedVersion':changed['definition']['version']},a['token'])
 reportbody={'reportName':prefix,'reportType':'table','sqlQuery':'SELECT amount FROM internal.rtdwh_views.'+a['name']+'_nested','filterConfig':'[]','isPublished':False}
 report=api('POST','/reports',reportbody,a['token']);state['reports'].append(report['id']);save()
 reportbody['isPublished']=True;api('PUT','/reports/'+str(report['id']),reportbody,a['token'])
 assert api('GET','/reports/'+str(report['id'])+'/data',token=a['token'])['rows']==[[31]]
 service=api('POST','/data-services',{'serviceCode':prefix,'serviceName':prefix,'sqlTemplate':reportbody['sqlQuery'],'parameterConfig':'[]','catalogName':'rtdwh_paimon','databaseName':'ods'},a['token']);state['services'].append(service['id']);save()
 service=api('POST','/data-services/'+str(service['id'])+'/publish',{'expectedRevision':service['revision']},token=a['token'])
 app=api('POST','/data-services/apps',{'appName':prefix},a['token']);state['apps'].append(app['id']);save()
 api('POST','/data-services/apps/'+str(app['id'])+'/grants',{'serviceId':service['id']},a['token'])
 creds={'X-App-Key':app['appKey'],'X-App-Secret':app['appSecret']}
 assert api('POST','/open/data/'+prefix,{},token='',headers=creds)['rows']==[[31]]
 print('PASS: real nested View, draft isolation, immutable versions, compatible republish, contract rejection, report and API reuse',flush=True)
 # Retarget only the API draft as admin. Frozen history, consumption and lineage must remain with A.
 servicepath='/data-services/'+str(service['id'])
 changed={'serviceCode':prefix,'serviceName':prefix,'sqlTemplate':'SELECT amount FROM internal.rtdwh_views.'+b['name'],
          'parameterConfig':'[]','catalogName':'rtdwh_paimon','databaseName':'ods','expectedRevision':service['revision']}
 service=api('PUT',servicepath,changed)
 assert api('POST','/open/data/'+prefix,{},token='',headers=creds)['rows']==[[31]]
 assert len(api('GET',servicepath+'/versions',token=a['token']))==1
 assert not api('GET',servicepath+'/versions',token=b['token'])
 assert not any(log['serviceId']==service['id'] for log in api('GET','/data-services/logs',token=b['token']))
 assert not any(item['id']==service['id'] for item in api('GET','/data-services',token=b['token']))
 context=api('GET','/dwh/assets/'+nested['asset']['assetId']+'/context',token=a['token'])
 assert any(u['kind']=='service' and u['id']==service['id'] and u['versionId']==service['publishedVersionId']
            and u['evidence']=='published_service_sql_ast' for u in context['usages'])
 api('POST',servicepath+'/publish',{'expectedRevision':service['revision']},reject=['无权','权限'])
 changed['sqlTemplate']=reportbody['sqlQuery'];changed['expectedRevision']=service['revision'];service=api('PUT',servicepath,changed)
 # App credentials must not keep a disabled or deauthorized owner's API running.
 api('POST','/admin/users/'+str(state['users'][0])+'/toggle-status')
 try: api('POST','/open/data/'+prefix,{},token='',headers=creds,reject=['停用'])
 finally: api('POST','/admin/users/'+str(state['users'][0])+'/toggle-status')
 original_permissions=a['body']['permissionIds']
 manage_id=next(p['id'] for p in perms if p['permCode']=='data-service:manage')
 a['body']['permissionIds']=[p for p in original_permissions if p!=manage_id];api('PUT','/admin/roles/'+str(a['role']),a['body'])
 try: api('POST','/open/data/'+prefix,{},token='',headers=creds,reject=['权限'])
 finally:
  a['body']['permissionIds']=original_permissions;api('PUT','/admin/roles/'+str(a['role']),a['body'])
 assert api('POST','/open/data/'+prefix,{},token='',headers=creds)['rows']==[[31]]
 print('PASS: API published lineage, retargeted draft/history isolation, disabled owner and permission revocation',flush=True)
 # Keep top/nested View permission but revoke the underlying table on an existing token.
 scopes=a['body']['dataScopes'];a['body']['dataScopes']=scopes[1:];api('PUT','/admin/roles/'+str(a['role']),a['body'])
 api('POST','/query/execute',{'sql':reportbody['sqlQuery']},a['token'],reject=['无权','权限'])
 api('GET','/reports/'+str(report['id'])+'/data',token=a['token'],reject=['无权','权限'])
 api('POST','/data-services/'+str(service['id'])+'/publish',{'expectedRevision':service['revision']},token=a['token'],reject=['无权','权限'])
 api('POST','/open/data/'+prefix,{},token='',headers=creds,reject=['无权','权限'])
 assert not api('GET',servicepath+'/versions',token=a['token'])
 assert not any(log['serviceId']==service['id'] for log in api('GET','/data-services/logs',token=a['token']))
 a['body']['dataScopes']=scopes;api('PUT','/admin/roles/'+str(a['role']),a['body'])
 # Remove a physical dependency, then restore and refresh only its external metadata.
 execute('ALTER TABLE ods.'+a['name']+' RENAME TO ods.'+a['name']+'_hidden')
 try:
  doris('REFRESH CATALOG rtdwh_paimon')
  health=api('GET','/dwh/views/'+nested['asset']['assetId']+'/health',token=a['token']);assert not health['valid']
  api('POST','/query/execute',{'sql':reportbody['sqlQuery']},a['token'],reject=['不存在','核验','失效','not exist','Unknown'])
 finally:
  execute('ALTER TABLE ods.'+a['name']+'_hidden RENAME TO ods.'+a['name']);doris('REFRESH CATALOG rtdwh_paimon')
 assert api('GET','/dwh/views/'+nested['asset']['assetId']+'/health',token=a['token'])['valid']
 # An unmanaged View and query-workbench DDL are both rejected.
 unmanaged=prefix+'_unmanaged';doris('CREATE VIEW internal.rtdwh_views.'+unmanaged+' AS '+reportbody['sqlQuery'])
 try:api('POST','/query/execute',{'sql':'SELECT * FROM internal.rtdwh_views.'+unmanaged},reject=['未托管'])
 finally:doris('DROP VIEW internal.rtdwh_views.'+unmanaged)
 api('POST','/query/execute',{'sql':'CREATE VIEW internal.rtdwh_views.forbidden AS SELECT 1'},reject=['仅支持','查询'])
 print('PASS: isolated role denial, existing-token recursive revocation, report/API revocation, missing dependency, unmanaged View and DDL rejection',flush=True)
 if '--keep' in sys.argv:save();print('Retained fixture assets:',','.join(v['assetId'] for v in state['views']),flush=True)
 else:cleanup();print('PASS: exact View fixtures cleaned',flush=True)
except Exception:
 if state['tables'] or state['views']:
  save();print('Fixtures recorded for exact cleanup at '+str(STATE),file=sys.stderr)
 raise
finally:
 if session:
  try:gw('DELETE','/v1/sessions/'+session)
  except Exception:pass

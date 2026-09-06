import React, { useEffect, useMemo, useRef } from 'react';
import Editor, { loader, type Monaco, type OnMount } from '@monaco-editor/react';
import * as monacoApi from 'monaco-editor/editor/editor.api';
import 'monaco-editor/languages/definitions/sql/register';
import type { editor, IDisposable, languages, Position } from 'monaco-editor';

type Props = {
  height?: string | number;
  value: string;
  catalog?: API.QueryCatalog;
  onChange: (value: string) => void;
  onExecute: () => void;
  onReady?: (editor: editor.IStandaloneCodeEditor) => void;
};

loader.config({ monaco: monacoApi });

const KEYWORDS = [
  'SELECT', 'FROM', 'WHERE', 'GROUP BY', 'ORDER BY', 'HAVING', 'LIMIT', 'JOIN', 'LEFT JOIN',
  'RIGHT JOIN', 'INNER JOIN', 'ON', 'AS', 'WITH', 'DISTINCT', 'COUNT', 'SUM', 'AVG', 'MIN',
  'MAX', 'CASE', 'WHEN', 'THEN', 'ELSE', 'END', 'AND', 'OR', 'NOT', 'IS NULL', 'IS NOT NULL',
  'SHOW CATALOGS', 'SHOW DATABASES', 'SHOW TABLES', 'DESCRIBE', 'EXPLAIN',
];

const SqlEditor: React.FC<Props> = ({ height = '300px', value, catalog, onChange, onExecute, onReady }) => {
  const disposables = useRef<IDisposable[]>([]);
  const executeRef = useRef(onExecute);
  executeRef.current = onExecute;

  const databases = useMemo(() => catalog?.databases || [], [catalog]);

  useEffect(() => () => {
    disposables.current.forEach((item) => item.dispose());
    disposables.current = [];
  }, []);

  const handleMount: OnMount = (instance, monaco: Monaco) => {
    disposables.current.forEach((item) => item.dispose());
    disposables.current = [];

    const rangeAt = (model: editor.ITextModel, position: Position) => {
      const word = model.getWordUntilPosition(position);
      return { startLineNumber: position.lineNumber, endLineNumber: position.lineNumber,
        startColumn: word.startColumn, endColumn: word.endColumn };
    };
    const item = (label: string, insertText: string, kind: languages.CompletionItemKind,
      detail: string, range: languages.CompletionItem['range']): languages.CompletionItem => ({
      label, insertText, kind, detail, range,
    });

    const provider = monaco.languages.registerCompletionItemProvider('sql', {
      triggerCharacters: ['.', ' '],
      provideCompletionItems: (model: editor.ITextModel, position: Position) => {
        const before = model.getValueInRange({
          startLineNumber: 1, startColumn: 1,
          endLineNumber: position.lineNumber, endColumn: position.column,
        });
        const range = rangeAt(model, position);
        const pathMatch = before.match(/([A-Za-z_][\w]*(?:\.[A-Za-z_][\w]*)*)\.$/);
        const path = pathMatch?.[1]?.split('.') || [];
        const suggestions: languages.CompletionItem[] = [];

        const addDatabases = () => databases.forEach((database) => suggestions.push(
          item(database.name, database.name, monaco.languages.CompletionItemKind.Module,
            `数据库 · ${database.tables.length} 张表`, range)));
        const addTables = (databaseName: string) => databases.find((database) => database.name === databaseName)
          ?.tables.forEach((table) => suggestions.push(item(
            table.name, table.name, monaco.languages.CompletionItemKind.Struct,
            `${table.layer.toUpperCase()} · ${table.columns.length} 个字段`, range)));
        const addColumns = (databaseName: string, tableName: string) => databases
          .find((database) => database.name === databaseName)?.tables
          .find((table) => table.name === tableName)?.columns
          .forEach((column) => suggestions.push(item(
            column.name, column.name, monaco.languages.CompletionItemKind.Field,
            `${column.type}${column.primaryKey ? ' · PK' : ''}`, range)));

        if (path.length) {
          if (path.length === 1 && path[0] === catalog?.catalogName) addDatabases();
          else if (path.length === 1 && databases.some((database) => database.name === path[0])) addTables(path[0]);
          else if (path.length === 2 && path[0] === catalog?.catalogName) addTables(path[1]);
          else if (path.length === 2) addColumns(path[0], path[1]);
          else if (path.length === 3 && path[0] === catalog?.catalogName) addColumns(path[1], path[2]);
          else {
            const alias = path[path.length - 1];
            const aliasPattern = new RegExp(`(?:from|join)\\s+(?:${catalog?.catalogName}\\.)?([\\w]+)\\.([\\w]+)(?:\\s+(?:as\\s+)?${alias})`, 'i');
            const aliasMatch = before.match(aliasPattern);
            if (aliasMatch) addColumns(aliasMatch[1], aliasMatch[2]);
          }
          return { suggestions };
        }

        KEYWORDS.forEach((keyword) => suggestions.push(item(
          keyword, keyword, monaco.languages.CompletionItemKind.Keyword, 'Doris SQL', range)));
        if (catalog) suggestions.push(item(
          catalog.catalogName, catalog.catalogName, monaco.languages.CompletionItemKind.Module,
          `Doris Paimon Catalog · ${catalog.catalogName}`, range));
        addDatabases();
        databases.forEach((database) => database.tables.forEach((table) => suggestions.push(item(
          `${database.name}.${table.name}`, `${database.name}.${table.name}`,
          monaco.languages.CompletionItemKind.Struct,
          `${table.layer.toUpperCase()} · ${table.columns.length} 个字段`, range))));
        return { suggestions };
      },
    });
    disposables.current.push(provider);

    instance.addAction({
      id: 'rtdwh.execute-query',
      label: '执行查询',
      keybindings: [monaco.KeyMod.CtrlCmd | monaco.KeyCode.Enter],
      run: () => executeRef.current(),
    });
    onReady?.(instance);
  };

  return (
    <Editor
      height={height}
      language="sql"
      theme="vs-dark"
      value={value}
      onChange={(next) => onChange(next || '')}
      onMount={handleMount}
      loading="正在加载 SQL 编辑器..."
      options={{
        automaticLayout: true,
        fontSize: 14,
        lineHeight: 22,
        minimap: { enabled: false },
        scrollBeyondLastLine: false,
        suggestOnTriggerCharacters: true,
        quickSuggestions: { other: true, comments: false, strings: false },
        wordWrap: 'on',
        padding: { top: 14, bottom: 14 },
        roundedSelection: true,
      }}
    />
  );
};

export default SqlEditor;
